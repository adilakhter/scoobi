/**
 * Copyright 2011,2012 National ICT Australia Limited
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
package com.nicta.scoobi
package impl
package rtt

import java.io._
import javassist._
import core._
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.conf.Configuration

/**
 * A class for building a class extending T at run-time.
 *
 * The purpose is to create a unique class which, when instantiated, will be able to perform the operations described in
 * T, while using some metadata object serialised to the distributed cache
 *
 * It takes:
 *
 *  - a type T which is going to be the parent of the generated class
 *  - the className (which must be unique for the class to build)
 *  - some metadata for the class (like a Map from tags to WireFormats, if it's a TaggedValue)
 *
 *  The metadata is distributed to the cache and a "metadataTag" method is added to the built class so that it can be retrieved.
 *
 *  For example, a TaggedValue is a class, defined for several tags, which is able to read/write values of different types (described by their WireFormats),
 *  with one type per tag. A concrete instance of that class will have a unique name, TV92, and will provide a method returning the
 *  path of the distributed cache file containing all WireFormats per tags.
 *
 *  The TaggedKey class then retrieves the map with the help of the ScoobiMetadata class and can use the wireformats to
 *  serialise/deserialise values
 *
 */
case class MetadataClassBuilder[T](className: String, metaData: Any, scoobiClassLoader: ClassLoader, configuration: Configuration)(implicit mf: Manifest[T]) {
  private lazy val logger = LogFactory.getLog("scoobi.MetadataClassBuilder")

  /** string value showing the generated class source code */
  lazy val show = {
    ctClass
    Seq("class "+className+" extends "+parentClassName+" {",
          indent(code.toString),
        "}").mkString("\n")
  }

  /** Compile the definition and code for the class. */
  def toRuntimeClass: RuntimeClass = new RuntimeClass(className, toClass, classBytecode)

  /** @return the java class */
  def toClass: Class[_] = {
    try { scoobiClassLoader.loadClass(className) } catch { case e: Throwable =>
      logger.debug(s"$className can't be loaded from the current class loader ($scoobiClassLoader): (${e.getMessage}), try creating the runtime class instead if not previously created")
      val r = ctClass.toClass
      scoobiClassLoader.loadClass(className)
      r
    }
  }

  private lazy val parentClassName = implicitly[Manifest[T]].runtimeClass.getName
  /** The compile-time representation of the class being built. */
  private def ctClass: CtClass = compileTimeClass.getOrElse(createCompileTimeClass)
  private var compileTimeClass: Option[CtClass] = None
  private def createCompileTimeClass: CtClass = {
    val ct = pool.makeClass(className, pool.get(parentClassName))
    build(ct)
    compileTimeClass = Some(ct)
    ct
  }

  private def build(ct: CtClass) = {
    val metadataTag = ScoobiMetadata.saveMetadata("scoobi.metadata."+className, metaData, configuration)
    addMethod(ct, "java.lang.String", "metadataTag", "return \""+metadataTag+"\";")
  }

  private lazy val pool: ClassPool = {
    val pool = new ClassPool
    pool.appendClassPath(new LoaderClassPath(scoobiClassLoader))
    pool.appendClassPath(new LoaderClassPath(getClass.getClassLoader))
    pool
  }
  /** The class bytecode */
  private lazy val classBytecode = {
    val bytecodeStream = new ByteArrayOutputStream
    ctClass.toBytecode(new DataOutputStream(bytecodeStream))
    bytecodeStream.toByteArray
  }

  /** add a parameter-less method to the class being built */
  private def addMethod(ct: CtClass, returnType: String, methodName: String, body: =>String): CtClass = addMethod(ct, returnType, methodName, Array(), body)
  /** add a method to the class being built */
  private def addMethod(ct: CtClass, returnType: String, methodName: String, parameters: Array[String] = Array(), body: =>String): CtClass = {
    code.append(returnType+" "+methodName+parameters.zipWithIndex.map { case (p, i) => "$"+(i+1)+": "+p }.mkString(" (", ", ", ") {\n"))
    code.append(indent(body))
    code.append("\n}\n\n")

    val method = CtNewMethod.make(pool.get(returnType), methodName, parameters.map(pool.get), Array(), body, ct)
    ct.addMethod(method)
    ct
  }

  private lazy val code = new StringBuilder

  private def indent(string: String) = {
    string.split("\n").map(line => if (line.nonEmpty) ("  "+line) else line).mkString("\n")
  }

}

