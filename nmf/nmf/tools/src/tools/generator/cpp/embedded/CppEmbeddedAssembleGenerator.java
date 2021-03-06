/*
 * Copyright (C) ST-Ericsson SA 2010. All rights reserved.
 *
 * This code is part of the NMF Toolset.
 *
 * The NMF Toolset is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * The NMF Toolset is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with the NMF Toolset. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package tools.generator.cpp.embedded;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;

import tools.common.compilerexception;
import tools.common.configuration;
import tools.conf.ast.astattribute;
import tools.conf.ast.astprovide;
import tools.conf.ast.astrequire;
import tools.conf.graph.component;
import tools.conf.graph.interfacereference;
import tools.conf.graph.primitive;
import tools.conf.graph.primitiveraw;
import tools.conf.graph.visitor;
import tools.conf.graph.computer.lifecycle;

public class CppEmbeddedAssembleGenerator implements tools.generator.api.AssembleGeneratorInterface 
{
    enum Action {
        setInclude, declare, 
        cppconstruct, cppdestroy,
        construct, destroy, start, stop};
    
    public int levelOfConstruction = 0;

    public static class subclient 
    {
        component client; 
        String clientitfname;
        public subclient(component _client, String _clientitfname)
        {
            client = _client;
            clientitfname = _clientitfname;
        }
    }

    public HashMap<String, HashSet<subclient>> callbacks = new HashMap<String, HashSet<subclient>>();

    class initializerOfLink extends visitor
    {
        PrintStream out;
        Action action;
        // bool second = false;
        public initializerOfLink(PrintStream _out, Action _action) 
        {
            out = _out;
            action = _action;
            if(action == Action.destroy || action == Action.stop)
                reserveOrder = true;
        }
        
        protected void setAttribute(primitive primitive) throws compilerexception 
        {
            // Declare Attributes
            for (astattribute attribute : primitive.getAttributes()) 
            {
                String value = primitive.getAttributeValue(attribute.name);
                if(value != null)
                    out.println("  _xyuv_" + primitive.getPathName_() + "." + attribute.name + " = " + value + ";");
            }
        }

        protected void setDependencies(primitiveraw primitive) throws compilerexception
        {
            // Bind require
            for (astrequire require : primitive.getRequires()) 
            {
                if(require.isStatic || require.virtualInterface)
                    continue;
                for(int i = 0; i < require.numberOfElement; i++) 
                {
                    String itfname = require.getInterfaceName(i);
                    interfacereference ir = primitive.lookupFcWithoutError(itfname);
                    String value;
                    if(ir == null)
                    {
                        // This is an unbinded interface
                        value = "0x0; /* Unbinded */";
                    } 
                    else if(ir.server == null)
                    {
                        // This is an external binding
                        value = "0x0; /* External */";
                        
                        // Register it for future use
                        HashSet<subclient> itfcb = callbacks.get(ir.itfname);
                        if(itfcb == null)
                        {
                            itfcb = new HashSet<subclient>();
                            callbacks.put(ir.itfname, itfcb);
                        }
                        itfcb.add(new subclient(primitive, itfname));
                    }
                    else
                    {
                        astprovide provide = ir.server.getProvide(ir.itfname);
                        // This is an internal binding
                        if(provide.collection || ! "".equals(provide.methprefix)) {
                            value = "&_xyuv_" + ir.server.getPathName_() + "._xywrap_" + ir.getInterfaceName() + ";";
                        } else {
                            value = "&_xyuv_" + ir.server.getPathName_() + ";";
                        }
                    }
                    out.println("  _xyuv_" + primitive.getPathName_() + "." + itfname + 
                            " = (" + require.type.replace(".", "_") + "Descriptor*)" + value);
                }
            }
        }

        protected void travelRawPrimitive(primitiveraw primitive, Object data) throws compilerexception 
        {     
            // Add specific initialization if need !
            switch (action) 
            {
            case setInclude: 
                out.println("#include <" + primitive.type.fullyname.replace(".", "/") + ".nmf>");
                break;
                
            case declare: 
                out.println("    " + primitive.type.fullyname_ + " _xyuv_" + primitive.getPathName_() +";  // " + primitive.priority);
                break;
                
            case cppconstruct: 
                String name = primitive.getPathName();
                if(primitive.debugComment != null) 
                    name = name + " # " + primitive.debugComment;
                out.println("  _xyuv_" + primitive.getPathName_() + ".name = \"" + name + "\";");
                
                setDependencies(primitive);

                setAttribute((primitive)primitive);
                break;
                
            case cppdestroy:
                break;
            
            case construct: 
                out.println("  // Construction of " + primitive.getPathName());
                
                if (primitive.getProvide("constructor") != null) 
                {
                    out.println("  if((error = _xyuv_" + primitive.getPathName_() + ".construct()) != NMF_OK)");
                    out.println("    goto out_on_error;");
                }
                out.println("  levelOfConstruction = " + ++levelOfConstruction + ";");
                
                out.println();
                break;
                
            case destroy:
                out.println("  // Destruction of " + primitive.getPathName());

                out.println("  if(levelOfConstruction >= " + levelOfConstruction-- + ") {");
                if (primitive.getProvide("destructor") != null) 
                    out.println("    _xyuv_" + primitive.getPathName_() + ".destroy();");
                out.println("  }");

                out.println();
                break;
                
            case start: 
                if (primitive.getProvide("starter") != null) 
                    out.println("  _xyuv_" + primitive.getPathName_() + ".start();");
                break;
                
            case stop:
                if (primitive.getProvide("stopper") != null) 
                    out.println("  _xyuv_" + primitive.getPathName_() + ".stop();");
                break;
                
            }
        }
    }
    
    public void generate(component instance, File dstfile, lifecycle lccstate) throws compilerexception, IOException 
    {
        PrintStream outh = configuration.forceRegeneration(
                configuration.getComponentFinalExt(instance.type, "hpp"));

        outh.println("/* '" + instance.type.fullyname_ + "' header library */");
        outh.println("#if !defined(" + instance.type.fullyname_ + "_NMF)");
        outh.println("#define " + instance.type.fullyname_ + "_NMF");
        outh.println();
        
        /**
         * Declare Composite class
         */
        outh.println("/* Static composition of " + instance.type.fullyname + " */");
        outh.println("#include <inc/type.h>");
        outh.println("#include <cpp.hpp>");
        outh.println();

        /*
         * Declare primitive component instance
         */
        outh.println("/*");
        outh.println(" * Declare composite class implementation");
        outh.println(" */");
        outh.println("class " + instance.type.fullyname_ + ": public NMF::Component {");
        outh.println("};");
        outh.println();

        outh.println("extern " + instance.type.fullyname_ + " &" + instance.type.smallname + "Network;");
        outh.println();
        
        outh.println("#endif");
        outh.close();

        /**
         * Generate C++ wrapper
         */
        PrintStream out = configuration.forceRegeneration(dstfile);

        out.println("#include \"" + instance.type.fullyname.replace(".", "/") + ".hpp\"");
        out.println();

        out.println("#define IN_NMF_ASSEMBLY");
        (new initializerOfLink(out,  Action.setInclude)).browseComponent(instance);
        out.println();

        out.println("/*");
        out.println(" * Declare composite class implementation");
        out.println(" */");
        out.println("class " + instance.type.fullyname_ + "Impl: public " + instance.type.fullyname_ + " {");
        out.println("  protected:");
        out.println("    t_sint16 levelOfConstruction;");
        (new initializerOfLink(out,  Action.declare)).browseComponent(instance);
        out.println();

        // Declare lifecycle methods
        out.println("  public:");
        out.println("    " + instance.type.fullyname_ + "Impl(void);");
        out.println("    ~" + instance.type.fullyname_ + "Impl(void);");
        out.println();

        out.println("    virtual t_nmf_error construct(void);");
        out.println("    virtual void start(void);");
        out.println("    virtual void stop(void);");
        out.println("    virtual t_nmf_error destroy(void);");
        out.println();

        out.println("    virtual t_nmf_error bindComponent(const char* name, NMF::Component* target, const char* targetname);");
        out.println("    virtual t_nmf_error unbindComponent(const char* name);");
        out.println("    virtual t_nmf_error getInterface(const char* name, NMF::InterfaceReference* reference);");
        out.println("    virtual t_nmf_error readAttribute(const char* name, t_uint32 *value);");
        out.println("    virtual t_nmf_error writeAttribute(const char* name, t_uint32 value);");

        out.println("};");
        out.println();

        out.println("static " + instance.type.fullyname_ + "Impl network;");
        out.println(instance.type.fullyname_ + " &" + instance.type.smallname + "Network = network;");
        out.println();

        out.println("/*");
        out.println(" *Declare primitive component instance and methods");
        out.println(" */");

        out.println("" + instance.type.fullyname_ + "Impl::" + instance.type.fullyname_ + "Impl() ");
        out.println("{");
        out.println("  levelOfConstruction = 0;");
        (new initializerOfLink(out, Action.cppconstruct)).browseComponent(instance);
        out.println("}");
        out.println();

        out.println("" + instance.type.fullyname_ + "Impl::~" + instance.type.fullyname_ + "Impl() { ");
        (new initializerOfLink(out, Action.cppdestroy)).browseComponent(instance);
        out.println("}");
        out.println();

        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::construct(void) {");
        out.println("  t_nmf_error error = NMF_OK;");        // Always declared in order to keep compiler happy
        (new initializerOfLink(out, Action.construct)).browseComponent(instance);
        out.println("  return error;");
        out.println("out_on_error:");
        out.println("  destroy();");
        out.println("  return error;");
        out.println("}");
        out.println();

        out.println("void " + instance.type.fullyname_ + "Impl::start(void) {");
        (new initializerOfLink(out, Action.start)).browseComponent(instance);
        out.println("}");
        out.println();

        out.println("void " + instance.type.fullyname_ + "Impl::stop(void) {");
        (new initializerOfLink(out, Action.stop)).browseComponent(instance);
        out.println("}");
        out.println();

        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::destroy(void) {");
        (new initializerOfLink(out, Action.destroy)).browseComponent(instance);
        out.println("  levelOfConstruction = 0;");
        out.println("  return NMF_OK;");
        out.println("}");
        out.println();

        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::getInterface(const char* name, NMF::InterfaceReference* reference) {");
        for (astprovide provide : instance.getProvides()) 
        {
            for(int i = 0; i < provide.numberOfElement; i++) 
            {
                String itfname = provide.getInterfaceName(i);
                interfacereference ir = instance.getFcInterface(itfname);

                if(ir != null)
                {
                    astprovide providesc = ir.server.getProvide(ir.itfname);

                    out.println("  if(compositeStrcmp(name, \"" + itfname + "\") == 0) {");
                    if(providesc.collection || ! "".equals(providesc.methprefix)) 
                        out.println("    *reference = (" +  provide.type.replace('.', '_') + "Descriptor*)&_xyuv_" + ir.server.getPathName_() + "." + ir.getInterfaceName() + ";");        
                    else 
                        out.println("    *reference = (" +  provide.type.replace('.', '_') + "Descriptor*)&_xyuv_" + ir.server.getPathName_() + ";");        
                    out.println("    return NMF_OK;");
                    out.println("  }");
                }
            }
        }
        out.println("  return NMF_NO_SUCH_PROVIDED_INTERFACE;");
        out.println("}");
        out.println();

        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::bindComponent(const char* name, NMF::Component* target, const char* targetname) {");
        out.println("  t_nmf_error error = NMF_NO_SUCH_REQUIRED_INTERFACE;");
        for(String cbname : callbacks.keySet())
        {
            astrequire require = instance.getRequire(astrequire.getNameOfInterfaceCollection(cbname));

            out.println("  if(compositeStrcmp(name, \"" + cbname + "\") == 0) {");
            out.println("    I" + require.type.replace('.', '_') + " itf;");
            out.println("    if((error = target->getInterface(targetname, &itf)) != NMF_OK)");
            out.println("      return error;");

            for(subclient scc : callbacks.get(cbname))
            {
                out.println("    _xyuv_" + scc.client.getPathName_() + "." + scc.clientitfname + " = itf;");
            }

            out.println("    return NMF_OK;");
            out.println("  }");
        }
        out.println("  return error;");
        out.println("}");
        out.println();

        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::unbindComponent(const char* name) {");
        for(String cbname : callbacks.keySet())
        {
            out.println("  if(compositeStrcmp(name, \"" + cbname + "\") == 0) {");

            for(subclient scc : callbacks.get(cbname))
            {
                out.println("    _xyuv_" + scc.client.getPathName_() + "." + scc.clientitfname + " = 0x0;");
            }

            out.println("    return NMF_OK;");
            out.println("  }");
        }
        out.println("  return NMF_NO_SUCH_REQUIRED_INTERFACE;");
        out.println("}");
        out.println();

        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::readAttribute(const char* name, t_uint32 *value) {");
        out.println("  return NMF_NO_SUCH_ATTRIBUTE;");
        out.println("}");
        out.println();
        
        out.println("t_nmf_error " + instance.type.fullyname_ + "Impl::writeAttribute(const char* name, t_uint32 value) {");
        out.println("  return NMF_NO_SUCH_ATTRIBUTE;");
        out.println("}");
        out.println();
        
        out.close();
    }

}
