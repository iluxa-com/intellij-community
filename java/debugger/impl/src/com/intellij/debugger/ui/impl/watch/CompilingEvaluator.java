/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.debugger.ui.impl.watch;

import com.intellij.debugger.DebuggerInvocationUtil;
import com.intellij.debugger.EvaluatingComputable;
import com.intellij.debugger.engine.ContextUtil;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.JVMNameUtil;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.*;
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator;
import com.intellij.debugger.engine.evaluation.expression.Modifier;
import com.intellij.debugger.impl.DebuggerUtilsEx;
import com.intellij.debugger.jdi.VirtualMachineProxyImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.JdkVersionUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.extractMethodObject.ExtractLightMethodObjectHandler;
import com.sun.jdi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.*;

/**
* @author egor
*/
public abstract class CompilingEvaluator implements ExpressionEvaluator {
  @NotNull protected final PsiElement myPsiContext;
  @NotNull protected final ExtractLightMethodObjectHandler.ExtractedData myData;

  public CompilingEvaluator(@NotNull PsiElement context, @NotNull ExtractLightMethodObjectHandler.ExtractedData data) {
    myPsiContext = context;
    myData = data;
  }

  @Override
  public Value getValue() {
    return null;
  }

  @Override
  public Modifier getModifier() {
    return null;
  }

  private TextWithImports getCallCode() {
    return new TextWithImportsImpl(CodeFragmentKind.CODE_BLOCK, myData.getGeneratedCallText());
  }

  @Override
  public Value evaluate(final EvaluationContext evaluationContext) throws EvaluateException {
    DebugProcess process = evaluationContext.getDebugProcess();

    ClassLoaderReference classLoader;
    try {
      classLoader = getClassLoader(evaluationContext, process);
    }
    catch (Exception e) {
      throw new EvaluateException("Error creating evaluation class loader: " + e, e);
    }

    String version = ((VirtualMachineProxyImpl)process.getVirtualMachineProxy()).version();
    JavaSdkVersion sdkVersion = JdkVersionUtil.getVersion(version);
    Collection<OutputFileObject> classes = compile(sdkVersion != null ? sdkVersion.getDescription() : null);

    try {
      defineClasses(classes, evaluationContext, process, classLoader);
    }
    catch (Exception e) {
      throw new EvaluateException("Error during classes definition " + e, e);
    }

    try {
      // invoke base evaluator on call code
      final Project project = ApplicationManager.getApplication().runReadAction(new Computable<Project>() {
        @Override
        public Project compute() {
          return myPsiContext.getProject();
        }
      });
      ExpressionEvaluator evaluator =
        DebuggerInvocationUtil.commitAndRunReadAction(project, new EvaluatingComputable<ExpressionEvaluator>() {
          @Override
          public ExpressionEvaluator compute() throws EvaluateException {
            final TextWithImports callCode = getCallCode();
            PsiElement copyContext = myData.getAnchor();
            final CodeFragmentFactory factory = DebuggerUtilsEx.findAppropriateCodeFragmentFactory(callCode, copyContext);
            return factory.getEvaluatorBuilder().
              build(factory.createCodeFragment(callCode, copyContext, project),
                    ContextUtil.getSourcePosition(evaluationContext));
          }
        });
      ((EvaluationContextImpl)evaluationContext).setClassLoader(classLoader);
      return evaluator.evaluate(evaluationContext);
    }
    catch (Exception e) {
      throw new EvaluateException("Error during generated code invocation " + e, e);
    }
  }

  private static ClassLoaderReference getClassLoader(EvaluationContext context, DebugProcess process)
    throws EvaluateException, InvocationException, InvalidTypeException, ClassNotLoadedException, IncompatibleThreadStateException {
    // TODO: cache
    ClassType loaderClass = (ClassType)process.findClass(context, "java.net.URLClassLoader", context.getClassLoader());
    Method ctorMethod = loaderClass.concreteMethodByName("<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    ClassLoaderReference reference = (ClassLoaderReference)process.newInstance(context, loaderClass, ctorMethod, Arrays.asList(createURLArray(context), context.getClassLoader()));
    keep(reference, context);
    return reference;
  }

  private static void keep(ObjectReference reference, EvaluationContext context) {
    ((SuspendContextImpl)context.getSuspendContext()).keep(reference);
  }

  private ClassType defineClasses(Collection<OutputFileObject> classes,
                                  EvaluationContext context,
                                  DebugProcess process,
                                  ClassLoaderReference classLoader)
    throws EvaluateException, InvalidTypeException, ClassNotLoadedException {

    VirtualMachineProxyImpl proxy = (VirtualMachineProxyImpl)process.getVirtualMachineProxy();
    for (OutputFileObject cls : classes) {
      if (cls.getName().contains(GEN_CLASS_NAME)) {
        Method defineMethod =
          ((ClassType)classLoader.referenceType()).concreteMethodByName("defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;");
        byte[] bytes = changeSuperToMagicAccessor(cls.toByteArray());
        ArrayList<Value> args = new ArrayList<Value>();
        StringReference name = proxy.mirrorOf(cls.myOrigName);
        keep(name, context);
        args.add(name);
        args.add(mirrorOf(bytes, context, process));
        args.add(proxy.mirrorOf(0));
        args.add(proxy.mirrorOf(bytes.length));
        process.invokeMethod(context, classLoader, defineMethod, args);
      }
    }
    return (ClassType)process.findClass(context, getGenClassQName(), classLoader);
  }

  private static byte[] changeSuperToMagicAccessor(byte[] bytes) {
    ClassWriter classWriter = new ClassWriter(0);
    ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5, classWriter) {
      @Override
      public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if ("java/lang/Object".equals(superName)) {
          superName = "sun/reflect/MagicAccessorImpl";
        }
        super.visit(version, access, name, signature, superName, interfaces);
      }
    };
    new ClassReader(bytes).accept(classVisitor, 0);
    return classWriter.toByteArray();
  }

  private static ArrayReference mirrorOf(byte[] bytes, EvaluationContext context, DebugProcess process)
    throws EvaluateException, InvalidTypeException, ClassNotLoadedException {
    ArrayType arrayClass = (ArrayType)process.findClass(context, "byte[]", context.getClassLoader());
    ArrayReference reference = process.newInstance(arrayClass, bytes.length);
    keep(reference, context);
    for (int i = 0; i < bytes.length; i++) {
      reference.setValue(i, ((VirtualMachineProxyImpl)process.getVirtualMachineProxy()).mirrorOf(bytes[i]));
    }
    return reference;
  }

  public static String getGeneratedClassName() {
    return GEN_CLASS_NAME;
  }

  private static final String GEN_CLASS_NAME = "GeneratedEvaluationClass";
  //private static final String GEN_CLASS_PACKAGE = "dummy";
  //private static final String GEN_CLASS_FULL_NAME = GEN_CLASS_PACKAGE + '.' + GEN_CLASS_NAME;
  //private static final String GEN_METHOD_NAME = "invoke";


  protected String getGenClassQName() {
    return ApplicationManager.getApplication().runReadAction(new Computable<String>() {
      @Override
      public String compute() {
        return JVMNameUtil.getNonAnonymousClassName(myData.getGeneratedInnerClass());
      }
    });
  }

  private static ArrayReference createURLArray(EvaluationContext context)
    throws EvaluateException, InvocationException, InvalidTypeException, ClassNotLoadedException, IncompatibleThreadStateException {
    DebugProcess process = context.getDebugProcess();
    ArrayType arrayType = (ArrayType)process.findClass(context, "java.net.URL[]", context.getClassLoader());
    ArrayReference arrayRef = arrayType.newInstance(1);
    keep(arrayRef, context);
    ClassType classType = (ClassType)process.findClass(context, "java.net.URL", context.getClassLoader());
    VirtualMachineProxyImpl proxy = (VirtualMachineProxyImpl)process.getVirtualMachineProxy();
    StringReference url = proxy.mirrorOf("file:a");
    keep(url, context);
    ObjectReference reference = process.newInstance(context, classType, classType.concreteMethodByName("<init>", "(Ljava/lang/String;)V"),
                                                    Collections.singletonList(url));
    keep(reference, context);
    arrayRef.setValues(Collections.singletonList(reference));
    return arrayRef;
  }

  ///////////////// Compiler stuff

  @NotNull
  protected abstract Collection<OutputFileObject> compile(String target) throws EvaluateException;

  private static URI getUri(String name, JavaFileObject.Kind kind) {
    return URI.create("memo:///" + name.replace('.', '/') + kind.extension);
  }

  protected static class SourceFileObject extends SimpleJavaFileObject {
    private final String myContent;

    SourceFileObject(String name, Kind kind, String content) {
      super(getUri(name, kind), kind);
      myContent = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignore) {
      return myContent;
    }
  }

  protected static class OutputFileObject extends SimpleJavaFileObject {
    private final ByteArrayOutputStream myStream = new ByteArrayOutputStream();
    private final String myOrigName;

    OutputFileObject(String name, Kind kind) {
      super(getUri(name, kind), kind);
      myOrigName = name;
    }

    byte[] toByteArray() {
      return myStream.toByteArray();
    }

    @Override
    public ByteArrayOutputStream openOutputStream() {
      return myStream;
    }
  }

  protected static class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    protected final Collection<OutputFileObject> classes = new ArrayList<OutputFileObject>();

    MemoryFileManager(JavaCompiler compiler) {
      super(compiler.getStandardFileManager(null, null, null));
    }

    @Override
    public OutputFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject source) {
      OutputFileObject mc = new OutputFileObject(name, kind);
      classes.add(mc);
      return mc;
    }
  }

}
