package com.rallyhealth.weepickle.v0.implicits.internal

import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import compat._
import acyclic.file
import com.rallyhealth.weepickle.v0.implicits.{discriminator, dropDefault, key}

import language.higherKinds
import language.existentials

/**
 * Implementation of macros used by weepickle to serialize and deserialize
 * case classes automatically. You probably shouldn't need to use these
 * directly, since they are called implicitly when trying to read/write
 * types you don't have a Reader/Writer in scope for.
 */
object Macros {

  trait DeriveDefaults[M[_]] {
    val c: scala.reflect.macros.blackbox.Context
    def fail(tpe: c.Type, s: String) = c.abort(c.enclosingPosition, s)

    import c.universe._
    def companionTree(tpe: c.Type): Either[String, Tree] = {
      val companionSymbol = tpe.typeSymbol.companion

      if (companionSymbol == NoSymbol && tpe.typeSymbol.isClass) {
        val clsSymbol = tpe.typeSymbol.asClass
        val msg = "[error] The companion symbol could not be determined for " +
          s"[[${clsSymbol.name}]]. This may be due to a bug in scalac (SI-7567) " +
          "that arises when a case class within a function is com.rallyhealth.weepickle.v0. As a " +
          "workaround, move the declaration to the module-level."
        Left(msg)
      }else{
        val symTab = c.universe.asInstanceOf[reflect.internal.SymbolTable]
        val pre = tpe.asInstanceOf[symTab.Type].prefix.asInstanceOf[Type]
        Right(c.universe.internal.gen.mkAttributedRef(pre, companionSymbol))
      }

    }
    def getArgSyms(tpe: c.Type) = {
      companionTree(tpe).right.flatMap { companion =>
        //tickle the companion members -- Not doing this leads to unexpected runtime behavior
        //I wonder if there is an SI related to this?
        companion.tpe.members.foreach(_ => ())
        tpe.members.find(x => x.isMethod && x.asMethod.isPrimaryConstructor) match {
          case None => Left("Can't find primary constructor of " + tpe)
          case Some(primaryConstructor) =>
            val flattened = primaryConstructor.asMethod.paramLists.flatten
            Right((
              companion,
              tpe.typeSymbol.asClass.typeParams,
              flattened,
              flattened.map(_.asTerm.isParamWithDefault)
            ))
        }

      }
    }

    /*
     * Play Json assumes None as the default for Option types, so None is serialized as missing from the output,
     * and when data is missing on input, deserialization assigns None to these fields. upickle only processes
     * defaults as specified explicitly. To be compatible deserializing JSON serialized with Play Json previously,
     * WeePickle will automatically force a default of None to all Option fields without an explicit default.
     */
    def deriveDefaults(companion: c.Tree, hasDefaults: Seq[Boolean], forceDefaultNone: Seq[Boolean]): Seq[c.Tree] = {
      val defaults =
        for(((hasDefault,forceDefault), i) <- hasDefaults.zip(forceDefaultNone).zipWithIndex)
        yield {
          val defaultName = TermName("apply$default$" + (i + 1))
          if (!hasDefault) {
            if (forceDefault) q"${TermName("None")}"
            else q"null"
          }
          else q"$companion.$defaultName"
        }
      defaults
    }

    /**
      * If a super-type is generic, find all the subtypes, but at the same time
      * fill in all the generic type parameters that are based on the super-type's
      * concrete type
      */
    def fleshedOutSubtypes(tpe: Type) = {
      for{
        subtypeSym <- tpe.typeSymbol.asClass.knownDirectSubclasses.filter(!_.toString.contains("<local child>"))
        if subtypeSym.isType
        st = subtypeSym.asType.toType
        baseClsArgs = st.baseType(tpe.typeSymbol).asInstanceOf[TypeRef].args
      } yield {
        tpe match{
          case ExistentialType(_, TypeRef(pre, sym, args)) =>
            st.substituteTypes(baseClsArgs.map(_.typeSymbol), args)
          case ExistentialType(_, _) => st
          case TypeRef(pre, sym, args) =>
            st.substituteTypes(baseClsArgs.map(_.typeSymbol), args)
        }
      }
    }

    def deriveObject(tpe: c.Type): c.universe.Tree = {
      val mod = tpe.typeSymbol.asClass.module
      val symTab = c.universe.asInstanceOf[reflect.internal.SymbolTable]
      val pre = tpe.asInstanceOf[symTab.Type].prefix.asInstanceOf[Type]
      val mod2 = c.universe.internal.gen.mkAttributedRef(pre, mod)

      annotate(tpe)(wrapObject(mod2))

    }
    def mergeTrait(subtrees: Seq[Tree], subtypes: Seq[Type], targetType: c.Type): Tree

    def derive(tpe: c.Type) = {
      if (tpe.typeSymbol.asClass.isTrait || (tpe.typeSymbol.asClass.isAbstract && !tpe.typeSymbol.isJava)) {
        val derived = deriveTrait(tpe)
        derived
      }
      else if (tpe.typeSymbol.isModuleClass) deriveObject(tpe)
      else deriveClass(tpe)
    }
    def deriveTrait(tpe: c.Type): c.universe.Tree = {
      val clsSymbol = tpe.typeSymbol.asClass

      if (!clsSymbol.isSealed) {
        fail(tpe, s"[error] The referenced trait [[${clsSymbol.name}]] must be sealed.")
      }else if (clsSymbol.knownDirectSubclasses.filter(!_.toString.contains("<local child>")).isEmpty) {
        val msg =
          s"The referenced trait [[${clsSymbol.name}]] does not have any sub-classes. This may " +
            "happen due to a limitation of scalac (SI-7046). To work around this, " +
            "try manually specifying the sealed trait picklers as described in " +
            "http://www.lihaoyi.com/upickle/#ManualSealedTraitPicklers"
        fail(tpe, msg)
      }else{
        val subTypes = fleshedOutSubtypes(tpe).toSeq
        //    println("deriveTrait")
        val subDerives = subTypes.map(subCls => q"implicitly[${typeclassFor(subCls)}]")
        //    println(Console.GREEN + "subDerives " + Console.RESET + subDrivess)
        val merged = mergeTrait(subDerives, subTypes, tpe)
        merged
      }
    }

    def typeclass: c.WeakTypeTag[M[_]]

    def typeclassFor(t: Type) = {
      //    println("typeclassFor " + weakTypeOf[M[_]](typeclass))

      weakTypeOf[M[_]](typeclass) match {
        case TypeRef(a, b, _) =>
          import compat._
          internal.typeRef(a, b, List(t))
        case ExistentialType(_, TypeRef(a, b, _)) =>
          import compat._
          internal.typeRef(a, b, List(t))
        case x =>
          println("Dunno Wad Dis Typeclazz Is " + x)
          println(x)
          println(x.getClass)
          ???
      }
    }

    def deriveClass(tpe: c.Type) = {
      getArgSyms(tpe) match {
        case Left(msg) => fail(tpe, msg)
        case Right((companion, typeParams, argSyms, hasDefaults)) =>
          // println(s"deriveClass: hasDefaults = $hasDefaults")
          // println("argSyms " + argSyms.map(_.typeSignature))
          // include .erasure to represent varargs as "Seq", not "Whatever*"
          val forceDefaultNone = hasDefaults.zip(argSyms.map(_.typeSignature.erasure.typeConstructor))
            .map { case (b, tc) => !b && tc.toString == "Option" }
          // println(s"deriveClass: forceDefaultNone = $forceDefaultNone")
          val rawArgs = argSyms.map(_.name.toString)
          val mappedArgs = argSyms.map { p =>
            customKey(p).getOrElse(p.name.toString)
          }

          def func(t: Type) = {

            if (argSyms.length == 0) t
            else {
              val concrete = tpe.dealias.asInstanceOf[TypeRef].args
              if (t.typeSymbol != definitions.RepeatedParamClass) {

                t.substituteTypes(typeParams, concrete)
              } else {
                val TypeRef(pref, sym, args) = typeOf[Seq[Int]]
                import compat._
                internal.typeRef(pref, sym, t.asInstanceOf[TypeRef].args)
              }
            }
          }
          // According to @retronym, this is necessary in order to force the
          // default argument `apply$default$n` methods to be synthesized
          companion.tpe.member(TermName("apply")).info

          val derive =
              // Otherwise, reading and writing are kinda identical
              wrapCaseN(
                companion,
                rawArgs,
                mappedArgs,
                argSyms.map(_.typeSignature).map(func),
                hasDefaults,
                forceDefaultNone,
                argSyms.map(shouldDropDefault),
                tpe,
                argSyms.exists(_.typeSignature.typeSymbol == definitions.RepeatedParamClass)
              )

          annotate(tpe)(derive)
      }
    }
    /** If there is a sealed base class, annotate the derived tree in the JSON
      * representation with a class label.
      */
    def annotate(tpe: c.Type)(derived: c.universe.Tree) = {
      val sealedParent = tpe.baseClasses.find(_.asClass.isSealed)
      sealedParent.fold(derived) { parent =>
        val tagName = customDiscriminator(parent) match {
          case Some(customName) => Literal(Constant(customName))
          case None => q"${c.prefix}.tagName"
        }
        val tag = customKey(tpe.typeSymbol).getOrElse(tpe.typeSymbol.fullName)
        q"""${c.prefix}.annotate($derived, $tagName, $tag)"""
      }
    }

    def customKey(sym: c.Symbol): Option[String] = {
        sym.annotations
          .find(_.tree.tpe == typeOf[key])
          .flatMap(_.tree.children.tail.headOption)
          .map{case Literal(Constant(s)) => s.toString}
    }

    def customDiscriminator(sym: c.Symbol): Option[String] = {
        sym.annotations
          .find(_.tree.tpe == typeOf[discriminator])
          .flatMap(_.tree.children.tail.headOption)
          .map{case Literal(Constant(s)) => s.toString}
    }

    /**
      * Unlike lihaoyi/upickle, rallyhealth/weepickle will write values even if they're
      * the same as the default value, unless instructed explicitly not to with the
      * [[dropDefault]] annotation.
      *
      * We are upgrading from play-json which always sends default values.
      * If teams swapped in weepickle for play-json and their rest endpoints started
      * omitting fields, that would be a surprising breaking API change.
      * The play-json will throw if a default valued field is missing,
      * e.g. Json.parse("{}").as[FooDefault] // throws: missing i
      *
      * Over time, consumers will transition to tolerant weepickle readers, and we
      * can revisit this.
      */
    def shouldDropDefault(sym: c.Symbol): Boolean = {
        sym.annotations
          .exists(_.tree.tpe == typeOf[dropDefault])
    }

    def wrapObject(obj: Tree): Tree

    def wrapCaseN(companion: Tree,
                  rawArgs: Seq[String],
                  mappedArgs: Seq[String],
                  argTypes: Seq[Type],
                  hasDefaults: Seq[Boolean],
                  forceDefaultNone: Seq[Boolean],
                  omitDefaults: Seq[Boolean],
                  targetType: c.Type,
                  varargs: Boolean): Tree
  }

  abstract class Reading[M[_]] extends DeriveDefaults[M] {
    val c: scala.reflect.macros.blackbox.Context
    import c.universe._
    def wrapObject(t: c.Tree) = q"new ${c.prefix}.SingletonR($t)"

    def wrapCaseN(companion: c.Tree,
                  rawArgs: Seq[String],
                  mappedArgs: Seq[String],
                  argTypes: Seq[Type],
                  hasDefaults: Seq[Boolean],
                  forceDefaultNone: Seq[Boolean],
                  omitDefaults: Seq[Boolean],
                  targetType: c.Type,
                  varargs: Boolean) = {
      val defaults = deriveDefaults(companion, hasDefaults, forceDefaultNone)
      if (rawArgs.size > 64) {
        c.abort(c.enclosingPosition, "weepickle does not support serializing case classes with >64 fields")
      }
      val localReaders = for (i <- rawArgs.indices) yield TermName("localReader" + i)
      val aggregates = for (i <- rawArgs.indices) yield TermName("aggregated" + i)
      q"""
        ..${
          for (i <- rawArgs.indices)
          yield q"private[this] lazy val ${localReaders(i)} = implicitly[${c.prefix}.Reader[${argTypes(i)}]]"
        }
        new ${c.prefix}.CaseR[$targetType]{
          override def visitObject(length: Int, index: Int) = new CaseObjectContext{
            ..${
              for (i <- rawArgs.indices)
              yield q"private[this] var ${aggregates(i)}: ${argTypes(i)} = _"
            }
            def storeAggregatedValue(currentIndex: Int, v: Any): Unit = currentIndex match{
              case ..${
                for (i <- rawArgs.indices)
                yield cq"$i => ${aggregates(i)} = v.asInstanceOf[${argTypes(i)}]"
              }
            }
            def visitKey(index: Int) = com.rallyhealth.weepickle.v0.core.StringVisitor
            def visitKeyValue(s: Any) = {
              currentIndex = ${c.prefix}.objectAttributeKeyReadMap(s.toString).toString match {
                case ..${
                  for(i <- mappedArgs.indices)
                  yield cq"${mappedArgs(i)} => $i"
                }
                case _ => -1
              }
            }

            def visitEnd(index: Int) = {
              ..${
                for(i <- rawArgs.indices if hasDefaults(i) || forceDefaultNone(i))
                yield q"if ((found & (1L << $i)) == 0) {found |= (1L << $i); storeAggregatedValue($i, ${defaults(i)})}"
              }

              // Special-case 64 because java bit shifting ignores any RHS values above 63
              // https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.19
              if (found != ${if (rawArgs.length == 64) -1 else (1L << rawArgs.length) - 1}){
                var i = 0
                val keys = for{
                  i <- 0 until ${rawArgs.length}
                  if (found & (1L << i)) == 0
                } yield i match{
                  case ..${
                    for (i <- mappedArgs.indices)
                    yield cq"$i => ${mappedArgs(i)}"
                  }
                }
                throw new com.rallyhealth.weepickle.v0.core.Abort(
                  "missing keys in dictionary: " + keys.mkString(", ")
                )
              }
              $companion.apply(
                ..${
                  for(i <- rawArgs.indices)
                  yield
                    if (i == rawArgs.length - 1 && varargs) q"${aggregates(i)}:_*"
                    else q"${aggregates(i)}"
                }
              )
            }

            def subVisitor: com.rallyhealth.weepickle.v0.core.Visitor[_, _] = currentIndex match{
              case -1 => com.rallyhealth.weepickle.v0.core.NoOpVisitor
              case ..${
                for (i <- rawArgs.indices)
                yield cq"$i => ${localReaders(i)} "
              }
            }
          }
        }
      """
    }
    def mergeTrait(subtrees: Seq[Tree], subtypes: Seq[Type], targetType: c.Type): Tree = {
      q"${c.prefix}.Reader.merge[$targetType](..$subtrees)"
    }
  }

  abstract class Writing[M[_]] extends DeriveDefaults[M] {
    val c: scala.reflect.macros.blackbox.Context
    import c.universe._
    def wrapObject(obj: c.Tree) = q"new ${c.prefix}.SingletonW($obj)"
    def findUnapply(tpe: Type) = {
      val (companion, paramTypes, argSyms, hasDefaults) = getArgSyms(tpe).fold(
        errMsg => c.abort(c.enclosingPosition, errMsg),
        x => x
      )
      Seq("unapply", "unapplySeq")
        .map(TermName(_))
        .find(companion.tpe.member(_) != NoSymbol)
        .getOrElse(c.abort(c.enclosingPosition, "None of the following methods " +
        "were defined: unapply, unapplySeq"))
    }

    def internal = q"${c.prefix}.Internal"
    def wrapCaseN(companion: c.Tree,
                  rawArgs: Seq[String],
                  mappedArgs: Seq[String],
                  argTypes: Seq[Type],
                  hasDefaults: Seq[Boolean],
                  forceDefaultNone: Seq[Boolean],
                  omitDefaults: Seq[Boolean],
                  targetType: c.Type,
                  varargs: Boolean) = {
      val defaults = deriveDefaults(companion, hasDefaults, forceDefaultNone)

      def checkDefault(i: Int) = hasDefaults(i) && omitDefaults(i)

      def write(i: Int) = {
        val snippet = q"""

          val keyVisitor = ctx.visitKey(-1)
          ctx.visitKeyValue(
            keyVisitor.visitString(
              ${c.prefix}.objectAttributeKeyWriteMap(${mappedArgs(i)}),
              -1
            )
          )
          val w = implicitly[${c.prefix}.Writer[${argTypes(i)}]]
          ctx.narrow.visitValue(w.write(ctx.subVisitor, v.${TermName(rawArgs(i))}), -1)
        """

        /**
          * @see [[shouldDropDefault()]]
          */
        if (checkDefault(i)) q"""if (v.${TermName(rawArgs(i))} != ${defaults(i)}) $snippet"""
        else snippet
      }
      q"""
        new ${c.prefix}.CaseW[$targetType]{
          def length(v: $targetType) = {
            var n = 0
            ..${
              for(i <- 0 until rawArgs.length)
              yield {
                if (!checkDefault(i)) q"n += 1"
                else q"""if (v.${TermName(rawArgs(i))} != ${defaults(i)}) n += 1"""
              }
            }
            n
          }
          def writeToObject[R](ctx: com.rallyhealth.weepickle.v0.core.ObjVisitor[_, R],
                               v: $targetType): Unit = {
            ..${(0 until rawArgs.length).map(write)}

          }
        }
       """
    }
    def mergeTrait(subtree: Seq[Tree], subtypes: Seq[Type], targetType: c.Type): Tree = {
      q"${c.prefix}.Writer.merge[$targetType](..$subtree)"
    }
  }
  def macroRImpl[T, R[_]](c0: scala.reflect.macros.blackbox.Context)
                         (implicit e1: c0.WeakTypeTag[T], e2: c0.WeakTypeTag[R[_]]): c0.Expr[R[T]] = {
    import c0.universe._
    val res = new Reading[R]{
      val c: c0.type = c0
      def typeclass = e2
    }.derive(e1.tpe)
//    println(res)
    c0.Expr[R[T]](res)
  }

  def macroWImpl[T, W[_]](c0: scala.reflect.macros.blackbox.Context)
                         (implicit e1: c0.WeakTypeTag[T], e2: c0.WeakTypeTag[W[_]]): c0.Expr[W[T]] = {
    import c0.universe._
    val res = new Writing[W]{
      val c: c0.type = c0
      def typeclass = e2
    }.derive(e1.tpe)
//    println(res)
    c0.Expr[W[T]](res)
  }
}

