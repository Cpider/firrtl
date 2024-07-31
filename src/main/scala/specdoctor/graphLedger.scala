// Copyright (C) 2011-2012 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// TODO: Fill in
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package specdoctor

import firrtl._
import firrtl.ir._

import scala.reflect.ClassTag
import scala.collection.mutable.ListBuffer
import scala.collection.mutable
import java.{util => ju}


// graphLedger sweeps fir file, build graphs of elements
object Node {
//  val types = Set("Port", "DefWire", "DefRegister", "DefNode", "DefMemory", "WDefInstance", "DefInstance")
  val types = Set("Port", "DefWire", "DefRegister", "DefNode", "DefMemory", "DefInstance")

  def apply(node: FirrtlNode): Node = {
    assert(Node.types.contains(node.getClass.getSimpleName),
      s"${node.serialize} is not an instance of Port/DefStatement\n")

    val name = node match {
      case port: Port => port.name
      case wire: DefWire => wire.name
      case reg: DefRegister => reg.name
      case nod: DefNode => nod.name
      case mem: DefMemory => mem.name
//      case winst: WDefInstance => winst.name
      case inst: DefInstance => inst.name
      case _ =>
        throw new Exception(s"${node.serialize} does not have name")
    }
    new Node(node, name)
  }

  def hasType(n: FirrtlNode): Boolean = types.contains(n.getClass.getSimpleName)

  def findName(expr: Expression): String = expr match {
    case WRef(refName, _, _, _) => refName
    case WSubField(e, _, _, _) => findName(e)
    case WSubIndex(e, _, _, _) => findName(e)
    case WSubAccess(e, _, _, _) => findName(e)
    case Reference(refName, _, _, _) => refName
    case SubField(e, _, _, _) => findName(e)
    case SubIndex(e, _, _, _) => findName(e)
    case SubAccess(e, _, _, _) => findName(e)
    case _ => // Mux, DoPrim, etc
      throw new Exception(s"${expr.serialize} does not have statement")
  }

  //noinspection ScalaStyle
  def findNames(expr: Expression): Set[String] = expr match {
    case WRef(refName, _, _, _) => Set(refName)
    case WSubField(e, _, _, _) => findNames(e)
    case WSubIndex(e, _, _, _) => findNames(e)
    case WSubAccess(e, _, _, _) => findNames(e)
    case Reference(refName, _, _, _) => Set(refName)
    case SubField(e, _, _, _) => findNames(e)
    case SubIndex(e, _, _, _) => findNames(e)
    case SubAccess(e, _, _, _) => findNames(e)
    case Mux(_, tval, fval, _) => findNames(tval) ++ findNames(fval)
    case DoPrim(_, args, _, _) => {
      var set = Set[String]()
      for (arg <- args) {
        set = set ++ findNames(arg)
      }
      set
    }
    case _ => Set[String]()
  }

}

class Node(val node: FirrtlNode, val name: String) {

  def serialize: String = this.node.serialize

  def get[T <: FirrtlNode : ClassTag]: T = node match {
    case t: T => t
    case _ =>
      throw new Exception(s"${node.serialize} mismatch")
  }

  def c: String = this.node.getClass.getSimpleName

  //noinspection ScalaStyle
  /* Check if Node is used for explicit data flow */
  def usedIn(expr: Expression, imp: Boolean = true): Boolean = expr match {
    case WRef(refName, _, _, _) => refName == name
    case WSubField(e, _, _, _) => usedIn(e)
    case WSubIndex(e, _, _, _) => usedIn(e) // Actually, it is not used in loFirrtl
    case WSubAccess(e, _, _, _) => usedIn(e) // This too
    case Reference(refName, _, _, _) => refName == name
    case SubField(e, _, _, _) => usedIn(e)
    case SubIndex(e, _, _, _) => usedIn(e)
    case SubAccess(e, _, _, _) => usedIn(e)
    case Mux(cond, tval, fval, _) =>
      if (imp) usedIn(cond) || usedIn(tval) || usedIn(fval) // Catch implicit data flow
      else usedIn(tval) || usedIn(fval)
    case DoPrim(_, args, _, _) => {
      var used = false
      for (arg <- args) {
        used = used | usedIn(arg)
      }
      used
    }
    case _ => false
  }

  /* Get port name of the expression for this node */
  def portIn(expr: Expression): Set[(String, Seq[String])] = {
    val ret = _getPort(expr).map((name, _))
    // Connected WDefInstance should have at lead one connected port
    assert(ret.nonEmpty, s"Does not have port: [${expr.serialize}]")

    ret
  }

  //noinspection ScalaStyle
  def _getPort(expr: Expression): Set[Seq[String]] = expr match {
    // WDefInstance
    case WSubField(e, n, _, _) => e match {
      case ref: WRef if (ref.name == name) => Set(Seq(n))
      case _ => _getPort(e).map(_ :+ n)
    }
    case WSubAccess(e, i, _, _) => e match {
      case ref: WRef if (ref.name == name) =>
        throw new NotImplementedError(s"_getPort: ${expr.serialize}")
      case _ => _getPort(e)
    }
    case wsa: WSubAccess => _getPort(wsa.expr)
    case wsi: WSubIndex => _getPort(wsi.expr)
    case Mux(cond, tval, fval, _) => _getPort(cond) ++ _getPort(tval) ++ _getPort(fval)
    case DoPrim(_, args, _, _) => args.map(_getPort(_)).reduce(_++_)
    case _ => Set()
  }
}

// graphLedger
// 1) Generate graph which consists of Statement (DefRegister, DefNode
// WDefInstance, Port)
// 2) Find all memory related to the 'Valid' io signals
// TODO: 3) Find all components controlling the memory related to the 'Valid' io signals
// TODO: 4) Continue over module boundary
class graphLedger(val module: DefModule) {
  val mName = module.name
  val mPorts = module.ports.toSet
  val inPorts = mPorts.filter(_.direction == Input)
  val outPorts = mPorts.filter(_.direction == Output)

  private val Nodes = mutable.Map[String, Node]()
  private val G = mutable.Map[String, Set[String]]()
  val R = mutable.Map[String, Set[String]]()
  val ER = mutable.Map[String, mutable.Map[Expression, Set[String]]]()
  var LoopER = Map[String, mutable.Map[Expression, Set[String]]]()
  val expER = mutable.Map[String, mutable.Map[Expression, Set[String]]]()
  val EG = mutable.Map[String, Set[(String, Expression)]]()
  val NT = mutable.Map[String, String]()
  val ISigER = mutable.Map[String, mutable.Map[String, Set[String]]]()
  val SigV = mutable.Map[String, Int]()
  val SigInV = mutable.Map[String, mutable.Map[String, Int]]()
  private val expG = mutable.Map[String, Set[String]]()
  private val expEG = mutable.Map[String, Set[(String, Expression)]]()
  val in_out_dep = mutable.Map[String, Set[String]]()
  val ldq_map = mutable.Map[String, String]()
  val uops_map = mutable.Map[String, mutable.Set[String]]()
  var dep_map = mutable.Map[String, Set[(String, String)]]()
  var Edep_map = mutable.Map[String, Set[(String, String)]]()
  var IinPorts = Set[String]()
  var IoutPorts = Set[String]()

  // r: reverse, N: node, E: Expression, M: mem, I: instance, P: port, 2: (sink) -> (source)
  val rN2IP = mutable.Map[String, Set[(String, String)]]()
  private val rN2MP = mutable.Map[String, Set[(String, String)]]()
  private val rMP2N = mutable.Map[(String, String), Map[String, String]]()
  // We use IP2E instead of IP2N because Expression preserves the connection info
  val rIP2E = mutable.Map[(String, String), Expression]()

  val N2E = mutable.Map[String, Expression]()

  private lazy val memorys: Set[DefMemory] = getNodes[DefMemory]
//  private lazy val instances: Set[WDefInstance] = getNodes[WDefInstance]
  private lazy val instances: Set[DefInstance] = getNodes[DefInstance]

  private val visited: mutable.Set[String] = mutable.Set()

  def getNodes[T <: FirrtlNode : ClassTag]: Set[T] = {
    Nodes.map(_._2.node).flatMap {
      case e: T => Some(e)
      case _ => None
    }.toSet
  }

  def IP2E: Map[(String, String), Expression] = rIP2E.toMap

  def parse: Unit = {
    this.module match {
      case ext: ExtModule =>
        print(s"$mName is external module\n")
      case mod: Module =>
        buildG
        reverseG
    }
  }

  def clear: Unit = {
    visited.clear
  }

  private def buildG: Unit = {
    this.module foreachPort findNode
    this.module foreachStmt findNode

    for ((n, _) <- G) {
      val sinks = ListBuffer[String]()
      val sinksExpr = ListBuffer[(String, Expression)]()
      val srcs = mutable.Map[Expression, Set[String]]()
      this.module foreachStmt findEdge(Nodes(n), sinks, sinksExpr, srcs)
      G(n) = sinks.toSet
      EG(n) = sinksExpr.toSet
      ER(n) = srcs

      expG(n) = Set[String]()
      expEG(n) = Set[(String, Expression)]()
      expER(n) = mutable.Map[Expression, Set[String]]()
    }

    for ((n, _) <- expG) {
      val sinks = ListBuffer[String]()
      val sinksExpr = ListBuffer[(String, Expression)]()
      val srcs = mutable.Map[Expression, Set[String]]()
      this.module foreachStmt findEdgeExp(Nodes(n), sinks, sinksExpr, srcs)
      expG(n) = sinks.toSet
      expEG(n) = sinksExpr.toSet
      expER(n) = srcs
    }

    insertIPsrc()
    dep_map = getPortExpr(ER)
    Edep_map = getPortExpr(expER)
    unrollIter(ER, ISigER)
    LoopER = getLoopExpr(ER)
    getInstPort
  }

  private def reverseG: Unit = {
    for ((n, _) <- G) {
      val sources = ListBuffer[String]()
      for ((m, sinks) <- G) {
        if (sinks.contains(n)) {
          sources.append(m)
        }
      }
      R(n) = sources.toSet
    }
  }

  private def findNode(s: FirrtlNode): Unit = {
    if (Node.hasType(s)) {
      val n = Node(s)
      Nodes(n.name) = n
      G(n.name) = Set[String]()
      EG(n.name) = Set[(String, Expression)]()
      ER(n.name) = mutable.Map[Expression, Set[String]]()
      if (n.name.endsWith("_T") || n.name.startsWith("_T") || n.name.startsWith("_GEN")) ISigER(n.name) = mutable.Map[String, Set[String]]()
    }

    s match {
      case stmt: Statement =>
        stmt foreachStmt findNode
      case other => Unit
    }
  }

  private def findEdgeExp(n: Node, sinks: ListBuffer[String], sinksExpr: ListBuffer[(String, Expression)], srcs: mutable.Map[Expression, Set[String]])(s: Statement): Unit = {
    s match {
      case reg: DefRegister =>
        if (n.usedIn(reg.reset, false)) {
          sinks.append(reg.name)
          reg.foreachExpr(expr => sinksExpr.append((reg.name, expr)))
        }
        if (reg.name == n.name) {
            val src = ListBuffer[String]()
            getSrc(reg.reset, src, false)
            srcs(reg.reset) = src.toSet
            // println(s"DefRegister ${srcs.mkString(", ")}")
        }
        // sinksExpr.append((reg.name, reg.serialize))
      case nod: DefNode =>
        if (n.usedIn(nod.value, false)) {
          sinks.append(nod.name)
          nod.foreachExpr(expr => sinksExpr.append((nod.name, expr)))
        }
        if (nod.name == n.name) {
          nod.foreachExpr{expr=>
            val src = ListBuffer[String]()
            getSrc(expr, src, false)
            srcs(expr) = src.toSet
            // println(s"DefNode ${srcs.mkString(", ")}")
          }
        }
          
        // sinksExpr.append((nod.name, nod.serialize))
      case Connect(_, l, e) =>
        if (n.usedIn(e, false)) {
          sinks.append(Node.findName(l))
          if (instances.map(_.name).contains(Node.findName(l)))
            sinksExpr.append((l.serialize, e))
          else
            sinksExpr.append((Node.findName(l), e))
        }
        if (Node.findName(l) == n.name) {
          val src = ListBuffer[String]()
          getSrc(e, src, false)
          srcs(e) = src.toSet
          // println(s"Connect ${srcs.mkString(", ")}")
        }
      case _ => Unit
    }

    s foreachStmt findEdgeExp(n, sinks, sinksExpr, srcs)
  }

  //noinspection ScalaStyle
  /* Find explicit data flow edges */
  private def findEdge(n: Node, sinks: ListBuffer[String], sinksExpr: ListBuffer[(String, Expression)], srcs: mutable.Map[Expression, Set[String]])(s: Statement): Unit = {
    s match {
      case reg: DefRegister =>
        if (n.usedIn(reg.reset)) {
          sinks.append(reg.name)
          reg.foreachExpr(expr => sinksExpr.append((reg.name, expr)))
          // sinksExpr.append((reg.name, reg.serialize))
        }
        if (reg.name == n.name) {
            val src = ListBuffer[String]()
            getSrc(reg.reset, src)
            srcs(reg.reset) = src.toSet
            // println(s"DefRegister ${srcs.mkString(", ")}")
        }
  
      case nod: DefNode =>
        if (n.usedIn(nod.value)) {
          sinks.append(nod.name)
          nod.foreachExpr(expr => sinksExpr.append((nod.name, expr)))
          // sinksExpr.append((nod.name, nod.serialize))

          updateN2XP(nod.name, nod.value, n)
        }
        updateN2E(nod.name, nod.value)
        if (nod.name == n.name) {
          nod.foreachExpr{expr=>
            val src = ListBuffer[String]()
            getSrc(expr, src)
            srcs(expr) = src.toSet
            // println(s"DefNode ${srcs.mkString(", ")}")
          }
        }
      case Connect(_, l, e) =>
        // l是instance的输入端口或者是模块的输入端口。e可能是模块output端口或者是内部变量。
        val lName = Node.findName(l) // lName可能是模块名或者是内部output名。
        if (n.usedIn(e)) {
          sinks.append(lName)
          if (instances.map(_.name).contains(lName))
            sinksExpr.append((l.serialize, e))
          else {
            sinksExpr.append((lName, e)) // 如果lName是模块名，G可能缺少了完整的变量。但是表达式没问题。
          }
          updateN2XP(lName, e, n)  // 如果n是模块名，即src是一个模块的output端口，则加入到rn2ip中，但是如果sink是模块input，lname是模块，只保存了模块。
          updateXP2X(lName, l, e, n)   //如果lname是模块，则保留（ins, output）和表达式。
          // updateP2E(lName, e)
        }
        updateN2E(lName, e)
        if (lName == n.name) {  // ER只有lName是模块的时候的sink是模块名的source，source和expr没问题。
          val src = ListBuffer[String]()
          getSrc(e, src)
          srcs(e) = src.toSet
          // println(s"Connect ${srcs.mkString(", ")}")
        }

      case _ => Unit // Port, DefWire, DefMemory, WDefInstance
    }

    s foreachStmt findEdge(n, sinks, sinksExpr, srcs) 
  }

    //noinspection ScalaStyle
  /* Check if Node is used for explicit data flow */
  def getSrc(expr: Expression, srcs: ListBuffer[String], imp: Boolean = true): Unit = expr match {
    case WRef(refName, _, _, _) => srcs.append(refName)
    case WSubField(e, ename, _, _) => {
      // getSrc(e, srcs)
      // println(s"WSubField ${e.serialize} ${ename}")
      srcs.append(expr.serialize)
    }
    case WSubIndex(e, _, _, _) => {
      getSrc(e, srcs) // Actually, it is not used in loFirrtl
      println(s"WSubIndex ${expr.serialize}")
    }
    case WSubAccess(e, _, _, _) => {
      getSrc(e, srcs) // This too
      println(s"WSubAccess ${expr.serialize}")
    }
    
    case Reference(refName, _, _, _) => srcs.append(refName)
    case SubField(e, ename, _, _) => {
      getSrc(e, srcs)
      println(s"SubField ${expr.serialize} ${ename}")
    }
    case SubIndex(e, _, _, _) => {
      getSrc(e, srcs)
      println(s"SubIndex ${expr.serialize}")
    }
    case SubAccess(e, _, _, _) => {
      getSrc(e, srcs)
      println(s"SubAccess ${expr.serialize}")
    }
    case Mux(cond, tval, fval, _) =>
      if (imp) {
        getSrc(cond, srcs)
        getSrc(tval, srcs)
        getSrc(fval, srcs)
      } // Catch implicit data flow
      else {
        getSrc(tval, srcs)
        getSrc(fval, srcs)
      }
    case DoPrim(_, args, _, _) => {
      for (arg <- args) {
        getSrc(arg, srcs)
      }
    }
    case _ => Unit
  }

  def getLoopExpr(ER: mutable.Map[String, mutable.Map[Expression, Set[String]]]): Map[String, mutable.Map[Expression, Set[String]]] = {
    ER.flatMap { case (dst, srcs) =>
    var loop = false
    srcs.foreach { case (expr, sigs) =>
      if (sigs.contains(dst)) loop = true
    }
    if (loop) Some(dst -> srcs)
    else None
  }.toMap
  }

//  def calculateValue(exprg: mutable.Map[String, Set[(String, Expression)]]): Unit = {
//    val inValid = inPorts.filter(_.name.contains("valid")).map(p => p.name)
//    val outValid = outPorts.filter(_.name.contains("valid")).map(p => p.name)
//    // Get the instance of this module and the port relationship with the port. Such as instance module out impact the out. in impact to instance input.
//
//    val parse_sig = mutable.Set[String]()
//    val not_parsed = mutable.Set[String]() ++ inValid
//    while (!not_parsed.isEmpty) {
//      val parse = not_parsed.head
//      if (!parse_sig.contains(parse)) {
//        parse_sig += parse
//        not_parsed -= parse
//
//      }
//    }
//
//  }

  object OpMask extends Enumeration {
    type value = Value
    val MuxCond =  Value(1 << 1)
    val MuxTval = Value(1 << 2)
    val MuxFval = Value(1 << 3)
    val OpAnd = Value(1 << 4)
    val OpNot = Value(1 << 5)
    val OpNeq = Value(1 << 6)
    val OpEq = Value(1 << 7)
    val OpOr =  Value(1 << 8)
    val OpXor = Value(1 << 9)
    val OpElse = Value(1 << 10)
  }

  def propValue(src: String, sink: String, expr: Expression, invalue: mutable.Map[String, mutable.Map[String, Int]], sigvalue: mutable.Map[String, Int]): Unit = expr match {
    case Mux(cond, tval, fval, _) =>
      if (!cond.isInstanceOf[Reference]) {
        propValue(src, cond.serialize, cond, invalue, sigvalue)
        if (sigvalue.getOrElse(cond.serialize, 3) != 3)  {
          SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
            case (input, score) =>
              invalue(sink).update(input, score | OpMask.MuxCond.id)
          }
          sigvalue(sink) = 2
        }
      }
      else {
        if (cond.serialize == src) {
          SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
            case (input, score) =>
              invalue(sink).update(input, score | OpMask.MuxCond.id)
          }
          sigvalue(sink) = 2
        }
      }
      if (!tval.isInstanceOf[Reference]) {
        propValue(src, tval.serialize, cond, invalue, sigvalue)
        if (sigvalue.getOrElse(tval.serialize, 3) != 3)  {
          SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
            case (input, score) =>
              invalue(sink).update(input, score | OpMask.MuxCond.id)
          }
          sigvalue(sink) = 1
        }
      }
      else {
        if (tval.serialize == src) {
          SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
            case (input, score) =>
              invalue(sink).update(input, score | OpMask.MuxTval.id)
          }
          sigvalue(sink) = 1
        }
      }
    if (!fval.isInstanceOf[Reference]) {
      propValue(src, fval.serialize, cond, invalue, sigvalue)
      if (sigvalue.getOrElse(fval.serialize, 3) != 3) {
        SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
          case (input, score) =>
            invalue(sink).update(input, score | OpMask.MuxFval.id)
        }
        sigvalue(sink) = 1
      }
    }
    else  {
      if (fval.serialize == src) {
        SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
          case (input, score) =>
            invalue(sink).update(input, score | OpMask.MuxFval.id)
        }
        sigvalue(sink) = 1
      }
    }

    case DoPrim(op, args, _, _) =>
      op match {
        case PrimOps.And =>
          //          val argsrc = if (args.head.serialize == src) args.head else args(1)
          val argsname = args.filter(_.isInstanceOf[Reference]).map(_.asInstanceOf[Reference].name)
          if (argsname.contains(src)) {
            SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
              case (input, score) =>
                invalue(sink).update(input, score | OpMask.OpAnd.id)
            }
            sigvalue(sink) = 1
          }

        case PrimOps.Not =>
          val argsname = args.filter(_.isInstanceOf[Reference]).map(_.asInstanceOf[Reference].name)
          if (argsname.contains(src)) {
            args.foreach { arg =>
              if (arg.serialize == src) {
                SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                  case (input, score) =>
                    invalue(sink).update(input, score | OpMask.OpNot.id)
                }
                sigvalue(sink) = 0
              }
            }
          }

        // case Neq => {
        //   args.foreachExpr {arg =>
        //     if (arg.serialize == src) {
        //       scoresig(sink) += scoresig.getOrElse(sink, mutable.Map[String, Int]()).map {
        //         case (I, S) =>
        //         (I, S | OpMask.OpNeq.id)
        //       }
        //     }
        //   }
        // }

        case PrimOps.Eq =>
          val argsname = args.filter(_.isInstanceOf[Reference]).map(_.asInstanceOf[Reference].name)
          if (argsname.contains(src)) {
            val argcmp = if (args.head.serialize != src) args.head else args(1)
            argcmp match {
              case UIntLiteral(vint, _)  =>
                if (vint > 0) {
                  SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                    case (input, score) =>
                      invalue(sink).update(input, score | OpMask.OpEq.id)
                  }
                  sigvalue(sink) = 1
                }
                else sigvalue(sink) = 0

              case SIntLiteral(vint, _) =>
                if (vint > 0) {
                  SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                    case (input, score) =>
                      invalue(sink).update(input, score | OpMask.OpEq.id)
                  }
                  sigvalue(sink) = 1
                }
                else sigvalue(sink) = 0

              case _ =>
                if (SigV.getOrElse(src, 0) != 0) {
                  SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                    case (input, score) =>
                      if (score > 0)
                        invalue(sink).update(input, score | OpMask.OpEq.id)
                  }
                  sigvalue(sink) = 1
                }
                else
                  sigvalue(sink) = 0
            }
          }

        case PrimOps.Or =>
          val argsname = args.filter(_.isInstanceOf[Reference]).map(_.asInstanceOf[Reference].name)
          if (argsname.contains(src)) {
            SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
              case (input, score) =>
                invalue(sink).update(input, score | OpMask.OpOr.id)
            }
            sigvalue(sink) = 1
          }

        case PrimOps.Xor =>
          val argsname = args.filter(_.isInstanceOf[Reference]).map(_.asInstanceOf[Reference].name)
          if (argsname.contains(src)) {
            val argcmp = if (args.head.serialize != src) args.head else args(1)
            argcmp match {
              case UIntLiteral(vint, _)  =>
                if (vint == 0) {
                  SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                    case (input, score) =>
                      invalue(sink).update(input, score | OpMask.OpEq.id)
                  }
                  sigvalue(sink) = 1
                }
                else sigvalue(sink) = 0

              case SIntLiteral(vint, _) =>
                if (vint == 0) {
                  SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                    case (input, score) =>
                      invalue(sink).update(input, score | OpMask.OpEq.id)
                  }
                  sigvalue(sink) = 1
                }
                else sigvalue(sink) = 0

              case _ =>
                if (SigV.getOrElse(src, 0) == 0) {
                  SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                    case (input, score) =>
                      if (score == 0)
                        invalue(sink).update(input, score | OpMask.OpEq.id)
                  }
                  sigvalue(sink) = 1
                }
                else sigvalue(sink) = 0
            }
          }

        case _ =>
          val argsname = args.filter(_.isInstanceOf[Reference]).map(_.asInstanceOf[Reference].name)
          if (argsname.contains(src)) {
                SigInV.getOrElse(src, mutable.Map[String, Int]()).foreach {
                  case (input, score) =>
                    invalue(sink).update(input, score | OpMask.OpElse.id)
                }
                sigvalue(sink) = 1
              }
      }

//    case Reference(name, _, _, _) =>
//      if (name == src) {
//        (src, sigvalue(src))
//      }
//      else ()

    case _ => Unit
  }

  def unrollIter(RExpr: mutable.Map[String, mutable.Map[Expression, Set[String]]], ISigExpr: mutable.Map[String, mutable.Map[String, Set[String]]]): Unit = {
    val ISigs = ISigExpr.keySet
    ISigs foreach { isig =>
      val visited = ListBuffer[String]()
      val in_parse = mutable.Set[String]()
      val op_expr = new StringBuilder()
      RExpr(isig).foreach {
        case (expr, signals) => 
          op_expr.append(expr.serialize)
          for (sig <- signals) {
            if (sig.startsWith("_GEN") || sig.endsWith("_T") || sig.startsWith("_T"))
            in_parse += sig
          }
      }
      while (in_parse.nonEmpty) {
        val parse = in_parse.head
        // if (mName.contains("LSU"))
        // println(s"parse is: ${parse}, visit is: ${visited}")
        if (!visited.contains(parse)) {
          visited += parse
          in_parse -= parse
          // println(s"parse ${parse}")
          if (ISigExpr(parse).nonEmpty) {
            // val repl = ISigExpr(parse).head._1
            visited ++= ISigExpr(parse).head._2.filter(visit => !in_parse.contains(visit))
            // println(s"op_expr ${op_expr}")
            // replaceAll(op_expr, parse, repl)
            // println(s"ISigExpr ${repl} ${parse} ${visited.size} ${in_parse.size}")
          }
          else if (RExpr(parse).nonEmpty) {
            var key = ""
            RExpr(parse).foreach {
              case (expr, sigs) =>
              if (sigs.nonEmpty) {
                key = expr.serialize
                for (sig <- sigs) {
                  if (!visited.contains(sig) && (sig.startsWith("_GEN") || sig.endsWith("_T") || sig.startsWith("_T"))){
                    in_parse += sig
                    // println(s"size: ${in_parse.size}, ${sig}")
                  }
                }
              }
            }
            replaceAll(op_expr, parse, key)
            // println(s"inparse ${in_parse.size} visited ${visited.size}")
          }
        }
      }
      // println(s"isig ${isig} op_expr ${op_expr.size} is: ${op_expr} ,visited ${visited.size}")
      ISigExpr(isig) += (op_expr.toString -> visited.toSet)
    }
  }

  def replaceAll(op_expr: StringBuilder, repl: String, dest: String): Unit = {
    var index = op_expr.indexOf(repl)
    var nextChar = ' '
    while (index != -1) {
      if (index + repl.length != op_expr.length && index != -1) 
      nextChar = op_expr.charAt(index + repl.length)
      if (nextChar.isDigit || nextChar == '_') {
        index = op_expr.indexOf(repl, index + repl.length)
        if (index + repl.length != op_expr.length && index != -1) 
        nextChar = op_expr.charAt(index + repl.length)
      }
      else {
        op_expr.replace(index, index + repl.length, dest)
        index = op_expr.indexOf(repl, index + repl.length)
        // println(s"op_expr ${op_expr.size}: ${op_expr} ${index} ${parse}")
        if (index + repl.length != op_expr.length && index != -1) 
        nextChar = op_expr.charAt(index + repl.length)
      }
      // println(s"${mName} op_expr ${op_expr.size}: ${repl} ${index} ${repl.length} ${dest.size}")
    }
  }

  def insertIPsrc(): Unit = {
    rIP2E.foreach{
      case ((ins, port), expr) =>
        val srcs = ListBuffer[String]()
        getSrc(expr, srcs)
        if (ER.contains(ins + "." + port)) 
        ER(ins + "." + port) += (expr -> srcs.toSet)
        else {
          ER(ins + "." + port) = mutable.Map[Expression, Set[String]]()
          ER(ins + "." + port) += (expr -> srcs.toSet)
        }
        for (src: String <- srcs.toSet) {
          EG(src) = EG.getOrElse(src, Set[(String, Expression)]()) ++ Set((ins + "." + port, expr))
        }
    }
    instances.map(_.name).foreach { case (insname) =>
      if (EG.contains(insname)) {
        EG(insname).foreach { case (sink, expr) =>
          val srcs = ListBuffer[String]()
          getSrc(expr, srcs)
          val psrc = srcs.filter(_.contains(insname))
          psrc.foreach { case (p) =>
            EG(p) = EG.getOrElse(p, Set.empty) ++ Set((sink, expr))
          }
        }
      }
    }
  }

  def getPortExpr(RExpr: mutable.Map[String, mutable.Map[Expression, Set[String]]]): mutable.Map[String, Set[(String, String)]] = {
    val inValid = inPorts.filter(_.name.contains("valid")).map(p => p.name)
    val outValid = outPorts.filter(_.name.contains("valid")).map(p => p.name)
    var dep = mutable.Map[String, Set[(String, String)]]()
    // val inDepPath = inValid foreach getDepPath
    // println(s"outValid is: ${outValid}, inValid is: ${inValid}")
    outValid foreach {  valid =>  
      val visited = ListBuffer[String]()
      val in_parse = mutable.Set[String]()
      val op_expr = new StringBuilder()
      RExpr(valid).foreach {
        case (expr, signals) => 
          op_expr.append(expr.serialize)
          in_parse ++= signals.toList
      }
      println(s"${mName} initial op_expr is: ${op_expr}")
      while (in_parse.nonEmpty) {
        val parse = in_parse.head
        // if (mName.contains("LSU"))
        // println(s"parse is: ${parse}, visit is: ${visited}")
        if (!visited.contains(parse)) {
          visited += parse
          in_parse -= parse
          if (parse.contains('.')) {
            val newTuple = (parse, op_expr.toString())
            dep = dep + (valid -> (dep.getOrElse(valid, Set()) + newTuple))
            
            if (RExpr.contains(parse)) {
              // if (mName.contains("LSU"))
              // println(s"ER port ${parse} size is: ${ER(parse).size}")
              var key = ""
              RExpr(parse).foreach {
                case (expr, sigs) =>
                if (sigs.nonEmpty) {
                  key = expr.serialize
                  for (sig <- sigs) {
                    if (!visited.contains(sig)){
                      in_parse += sig
                    }
                  }    
                }
              }
              var index = op_expr.indexOf(parse)
              // while (index >= 0) {
                op_expr.replace(index, index + parse.length, key)
                // index = op_expr.indexOf(parse, index + key.length)
              // }
              // if (mName.contains("LSU"))
              // println(s"op_expr is: ${op_expr}, key is: ${key}")
            }
          }
          else if(inValid.contains(parse)) {
            val newTuple = (parse, op_expr.toString())
            dep = dep + (valid -> (dep.getOrElse(valid, Set()) + newTuple))
          }
          else if(outValid.filter(!_.contains(valid)).contains(parse)) {
            val newTuple = (parse, op_expr.toString())
            dep = dep + (valid -> (dep.getOrElse(valid, Set()) + newTuple))
          }
          else {
            if (RExpr(parse).nonEmpty) {
              // if (mName.contains("LSU"))
              // println(s"ER sig ${parse} size is: ${ER(parse).size}")
              
              var key = ""
              RExpr(parse).foreach {
                case (expr, sigs) =>
                if (sigs.nonEmpty) {
                  key = expr.serialize
                  for (sig <- sigs) {
                    if (!visited.contains(sig)){
                      in_parse += sig
                    }
                  }
                }
              }
              var index = op_expr.indexOf(parse)
              var nextChar = ' '
              if (index + parse.length != op_expr.length && index != -1) 
              nextChar = op_expr.charAt(index + parse.length)
              while ((nextChar.isDigit || nextChar == '_') && index != -1) {
                index = op_expr.indexOf(parse, index + parse.length)
                nextChar = op_expr.charAt(index + parse.length)
              }
                // println(s"op_expr is: ${op_expr}, key is: ${key}")
              if (index != -1)
                op_expr.replace(index, index + parse.length, key)
              
              
                // index = op_expr.indexOf(parse, index + key.length)
              // }
              // if (mName.contains("LSU"))
              // println(s"op_expr is: ${op_expr}, key is: ${key}")
            }
          }
        }  
         
        // if (mName.contains("Rob")){
        //   // println(s"op_expr size is: ${op_expr.size}, op_expr is: ${op_expr}")
        //   // println(s"visited size is: ${visited.size}. is: ${visited.mkString(", ")}")
        //   // println(s"in_parse size is: ${in_parse.size}. is: ${in_parse.mkString(", ")}")
        //   println(s"op_expr size is: ${op_expr.size}")
        //   println(s"visited size is: ${visited.size}")
        //   println(s"in_parse size is: ${in_parse.size}")
        // }
        
      }
      // println(s"op_expr size is: ${op_expr.size}, op_expr is: ${op_expr}") 
      op_expr.delete(0, op_expr.length)
    }
    println(s"module is: ${mName} dep is: ${dep}")
    dep
  }

  // def getDepPath(src: String, sinks: Set[String]): Map[String, Set[String]] = {
  //   val visited = mutable.Set[String]()
  //   val notVisited = mutable.Set[String]() + src

  // }

  def getInstPort: Unit = {
    IoutPorts = rN2IP.values.flatMap(_.map { case (str1, str2) => s"$str1.$str2" }).toSet
    IinPorts = rIP2E.keySet.map{x => s"${x._1}.${x._2}" }.toSet
  }

  // rN2IP, rN2MP update
  private def updateN2XP(sink: String, srcE: Expression, node: Node): Unit = {
    node.c match {
//      case "WDefInstance" =>
//        rN2IP(sink) = rN2IP.getOrElse(sink, Set()) ++
//          node.portIn(srcE).map(x => (x._1, x._2.head))
      case "DefInstance" =>
        rN2IP(sink) = rN2IP.getOrElse(sink, Set()) ++
          node.portIn(srcE).map(x => (x._1, x._2.head))
      case "DefMemory" =>
        rN2MP(sink) = rN2MP.getOrElse(sink, Set()) ++
          node.portIn(srcE).map(x => (x._1, x._2.head))
      case _ => Unit
    }
  }

  // rIP2E, rMP2N update
  private def updateXP2X(sink: String, sinkE: Expression, srcE: Expression, node: Node): Unit = {
    Seq(instances, memorys).map(_.exists(_.name == sink)) match {
      case Seq(true, true) =>
        throw new Exception(s"${sink} contains in both instances and memorys")
      case Seq(true, false) =>
        val IP = Nodes(sink).portIn(sinkE).head // Only one port is connected at once
        rIP2E((IP._1, IP._2.head)) = srcE
      case Seq(false, true) =>
        val MPF = Nodes(sink).portIn(sinkE).head // Only one port is connected at once
        val MP = (MPF._1, MPF._2.head)
        rMP2N(MP) = rMP2N.getOrElse(MP, Map()) + (MPF._2.last -> node.name)
      case _ => Unit
    }
  }

  // N2E
  private def updateN2E(sink: String, srcE: Expression): Unit = {
//    println(s"module is $mName, sink is $sink, express is: $srcE")
//    println(s"$Nodes")
    if (Set("DefMemory", "DefInstance").contains(Nodes(sink).c))
//      if (Set("WDefInstance", "DefMemory", "DefInstance").contains(Nodes(sink).c))
      return
    if (N2E.keySet.contains(sink))
      return

    N2E(sink) = srcE
  }

  def getInstanceMap: Map[String, (String, Set[(String, Set[String])])] = {
    val I2IP = instances.flatMap(i => {
      visited.clear
      val (srcs, ips) = findNodeSrcs(Set(i.name))
//      srcs("WDefInstance").map(x =>
//        (x, i.name, ips(x.get[WDefInstance])))
      srcs("DefInstance").map(x =>
        (x, i.name, ips(x.get[DefInstance])))
    })

    instances.map(i => {
      val sink_srcPs = I2IP.flatMap{ case (srcN, sink, ports) =>
//        if (srcN.get[WDefInstance] == i)
        if (srcN.get[DefInstance] == i)
          Some((sink, ports.filter(_.contains("valid"))))
        else
          None
      }
      (i.name , (i.module, sink_srcPs))
    }).toMap
  }

  def findPortSrcs(ports: Set[String]):
//      (Map[String, Set[Node]], Map[WDefInstance, Set[String]]) = {
    (Map[String, Set[Node]], Map[DefInstance, Set[String]]) = {
    /* ports should be subset of outward ports of module *
     * Current design cannot discriminate outward ports and inward ports,
     * so just select only outward */
    assert(ports.subsetOf(mPorts.map(_.name)),
           s"$mName does not have ports: {${ports.diff(mPorts.map(_.name))}}")

    val oPorts = ports.intersect(mPorts.filter(_.direction == Output).map(_.name))

    findNodeSrcs(oPorts)
  }

  def findExprSrcs(exprs: Set[Expression]):
//      (Map[String, Set[Node]], Map[WDefInstance, Set[String]]) = {
    (Map[String, Set[Node]], Map[DefInstance, Set[String]]) = {
    val exprNodes = exprs.flatMap(Node.findNames)

    // Select exact (input) ports
    val inPorts = mPorts.filter(_.direction == Input).map(_.name)
    val inputs = exprNodes.intersect(inPorts).diff(visited)
    visited ++= inputs

    // Select memorys (TODO: continue over the ports connected to the memory)
    val mems = exprNodes.intersect(memorys.map(_.name)).diff(visited)
    visited ++= mems

    // Select instances (and its port)
    val insts = exprNodes.intersect(instances.map(_.name)).map(Nodes)
    val iPorts = insts.map(i =>
//      (i.get[WDefInstance], exprs.filter(i.usedIn(_)).flatMap(i.portIn).map(_._2.head))
        (i.get[DefInstance], exprs.filter(i.usedIn(_)).flatMap(i.portIn).map(_._2.head))
    ).toMap

    val (iNodes, oIPorts, iMems) = findOutPortsConnected(iPorts)

    val nodes = exprNodes.diff(insts.map(_.name) ++ mems) ++ iNodes
    val (nodeSrcs, instPorts) = findNodeSrcs(nodes)

    val retNodeSrcs = Map(
      "DefRegister" -> nodeSrcs("DefRegister"),
      "DefMemory" -> (nodeSrcs("DefMemory") ++ mems.map(Nodes) ++ iMems.map(Nodes)),
//      "WDefInstance" -> (nodeSrcs("WDefInstance") ++ oIPorts.keySet.map(i => Nodes(i.name))),
      "DefInstance" -> (nodeSrcs("DefInstance") ++ oIPorts.keySet.map(i => Nodes(i.name))),
      "Port" -> (nodeSrcs("Port") ++ inputs.map(Nodes))
    )
    val retInstPorts = (oIPorts.keySet ++ instPorts.keySet).map(key =>
      (key, oIPorts.getOrElse(key, Set()) ++ instPorts.getOrElse(key, Set()))
    ).toMap

    (retNodeSrcs, retInstPorts)
  }

  /*  If instance and port (i.e., (inst, port)) is found,
      it can be an input port which still needs backward slicing further.
      We find all Nodes and 'real' output ports which are sources
  */
  private def findOutPortsConnected(ips: Map[DefInstance, Set[String]]):
//  private def findOutPortsConnected(ips: Map[WDefInstance, Set[String]]):
//      (Set[String], Map[WDefInstance, Set[String]], Set[String]) = {
    (Set[String], Map[DefInstance, Set[String]], Set[String]) = {
    val oIPorts = ips.map{
      case (wdi, s) => (wdi, s.filter(p => !rIP2E.keySet.contains((wdi.name, p))))
    }.filter(_._2.nonEmpty)

    val exprs = rIP2E.filterKeys(ips.map{
      case (wdi, s) => s.map(p => (wdi.name, p))
    }.flatten.toSet.contains).values.toSet
    val exprNodes = exprs.flatMap(Node.findNames)

    val insts = exprNodes.intersect(instances.map(_.name))
    val mems = exprNodes.intersect(memorys.map(_.name))
    val nodes = exprNodes.diff(insts ++ mems)

    val iPorts = insts.map(i => {
      val n = Nodes(i)
//      (n.get[WDefInstance], exprs.filter(n.usedIn(_)).flatMap(n.portIn).map(_._2.head))
      (n.get[DefInstance], exprs.filter(n.usedIn(_)).flatMap(n.portIn).map(_._2.head))
    }).toMap.filter(_._2.nonEmpty)

    if (iPorts.isEmpty) {
      (nodes, oIPorts, mems)
    } else {
      val cont = findOutPortsConnected(iPorts)
      val retOIPorts = (oIPorts.keySet ++ cont._2.keySet).map(key =>
        (key, oIPorts.getOrElse(key, Set()) ++ cont._2.getOrElse(key, Set()))
      ).toMap
      (nodes ++ cont._1, retOIPorts, mems ++ cont._3)
    }
  }

  //noinspection ScalaStyle
  private def findNodeSrcs(nodes: Set[String]):
//      (Map[String, Set[Node]], Map[WDefInstance, Set[String]]) = {
    (Map[String, Set[Node]], Map[DefInstance, Set[String]]) = {

    val nodeSrcs = Map[String, ListBuffer[Node]](
      "DefRegister" -> ListBuffer[Node](),
      "DefWire" -> ListBuffer[Node](),
      "DefNode" -> ListBuffer[Node](),
      "DefMemory" -> ListBuffer[Node](),
//      "WDefInstance" -> ListBuffer[Node](),
      "DefInstance" -> ListBuffer[Node](),
      "Port" -> ListBuffer[Node]())
//    val instPorts = mutable.Map[WDefInstance, Set[String]]()
    val instPorts = mutable.Map[DefInstance, Set[String]]()


    val srcs = nodes.flatMap(n => {
      val fSrcs = R(n)
      visited.add(n)
      fSrcs.flatMap(findSrcs(_, n))})

    val allSrcs = srcs.foldLeft(Map[String, Set[String]]())((res, s) => {
      val seq = res.getOrElse(s._1, Set[String]())
      s._2 match {
        case None => res + (s._1 -> seq)
        case Some(str) => res + (s._1 -> (seq + str))
      }}).toSet

    for (src <- allSrcs) {
      Nodes(src._1).c match {
        case "DefRegister" => nodeSrcs("DefRegister").append(Nodes(src._1))
        case "DefWire" => nodeSrcs("DefWire").append(Nodes(src._1))
        case "DefNode" => nodeSrcs("DefNode").append(Nodes(src._1))
        case "DefMemory" => nodeSrcs("DefMemory").append(Nodes(src._1))
//        case "WDefInstance" => {
//          instPorts(Nodes(src._1).get[WDefInstance]) = src._2
//          nodeSrcs("WDefInstance").append(Nodes(src._1))
//        }
        case "DefInstance" => {
          instPorts(Nodes(src._1).get[DefInstance]) = src._2
          nodeSrcs("DefInstance").append(Nodes(src._1))
        }
        case "Port" =>
          if (Nodes(src._1).get[Port].direction == Input &&
            !Set("clock", "reset").contains(src._1))
            nodeSrcs("Port").append(Nodes(src._1))
        case _ =>
          throw new Exception(s"${src} not in Node type")
      }
    }

    (nodeSrcs.map(tuple => (tuple._1, tuple._2.toSet)), instPorts.toMap)
  }

  def isStatePreserving(reg: DefRegister): Boolean = {
    R(reg.name).map(s => Nodes(s).c match {
      case "DefRegister" => s == reg.name
      case _ => false
    }).reduce(_ || _)
  }

  def findVecRegs(regs: Set[DefRegister]): Map[String, Set[DefRegister]] = {

    val infoRegMap: Map[Info, Seq[DefRegister]]  = {
      regs.foldLeft(ListBuffer[Info]())((list, reg) => {
        if (list.contains(reg.info)) list
        else list :+ reg.info
      }).map(info => (info, regs.filter(_.info == info).toSeq)).toMap
    }

    val MINVECSIZE = 2
    val sInfoRegMap = infoRegMap.map{ case (i, seq) =>
      (i, seq.sortBy(_.name)) }.filter{ case (i, seq) =>
      (i.getClass.getSimpleName != "NoInfo" && seq.length >= MINVECSIZE)
    }

    val retInfoRegs = mutable.Map[String, Set[DefRegister]]()
    for ((i, seq) <- sInfoRegMap) {
      val regs = seq.map(_.name)
      val prefix = regs.foldLeft(regs.head.inits.toSet)((set, reg) => {
        reg.inits.toSet.intersect(set)
      }).maxBy(_.length)

      prefix.length match {
        case 0 => Unit
        case n => {
          val bodies = regs.map(x => {x.substring(n, x.length)} )

          if (bodies.forall(b => b.length > 0 && b(0).isDigit)) {
            val hyphenOrEnd =
              (b: String) => {if (b.contains('_')) b.indexOf('_') else b.length}
            val idxs = bodies.map(b => b.substring(0, hyphenOrEnd(b)))
            val vElems = idxs.map(i =>
              (i.toInt, bodies.collect{
                 case b if b.substring(0, hyphenOrEnd(b)) == i =>
                   b.substring(i.length, b.length)
               })
            ).toMap

            val idxStream = vElems.keySet.toSeq.sorted.sliding(2)
            if (idxStream.count(k => k(0) + 1 == k(1)) == vElems.keySet.size - 1 &&
              vElems.keySet.toSeq.head == 0 &&
              vElems.forall(_._2.toSet == vElems.head._2.toSet)) {
              retInfoRegs(prefix) = seq.toSet
            }
          }
        }
      }
    }

    retInfoRegs.toMap
  }

  private def findSrcs(sink: String, prev: String): Seq[(String, Option[String])] = {
    assert(R.keySet.contains(sink),
      s"R does not contain $sink")

    if (visited.contains(sink))
      return Seq()

    val sources = R(sink)

    // WDefInstance does not form a loop & Can be reached multiple times with different ports
//    if (!Set("WDefInstance", "DefMemory").contains(Nodes(sink).c))
      if (!Set("DefInstance", "DefMemory").contains(Nodes(sink).c))
      visited.add(sink)

    sources.foldLeft(Nodes(sink).node match {
//      case winst: WDefInstance =>
      case winst: DefInstance =>
        /* TODO: If inst-port is input, continue over */
        rN2IP(prev).map(x => (x._1, Option.apply[String](x._2))).toSeq
      case mem: DefMemory =>
        /* TODO: When memory (data port) is detected, two fields should be sliced
            1. the address selection logic
            2. the writer logic, which modifies the memory value
        */
        val cons = rN2MP(prev).flatMap(x =>
          rMP2N.getOrElse(x, throw new Exception(s"$x not in rMP2N"))
            .filterKeys(Seq().contains).values.toSet)
        (rN2MP(prev).map(x => (x._1, Option.apply[String](x._2))) ++
          cons.flatMap(findSrcs(_, sink))).toSeq
      case _ => Seq((sink, Option.empty[String])) // Port, DefRegister, DefWire, DefNode
    }) ((list, str) => Nodes(sink).c match {
      case "DefInstance" | "DefMemory" => list
//      case "WDefInstance" | "DefMemory" => list
      case _ => list ++ findSrcs(str, sink) // DefNode, DefWire, Port
    })
  }

  def findNodeSinks(n: String): Set[String] = getOuter(findSinks(n).toSet)

  def findMemSinks(mem: DefMemory): (Set[String], Set[String]) = {
    val readers = mem.readers
    val addrs = rMP2N.flatMap{ case (k, map) =>
      if (k._1 == mem.name && readers.contains(k._2))
        map.mapValues(Some(_)).getOrElse("addr", None)
      else
        None
    }
    val datas = rN2MP.collect{
      case (n, mp) if mp.exists(x => x._1 == mem.name && readers.contains(x._2)) => n
    }
    val nodes = datas.flatMap(findSinks)

    (addrs.toSet, getOuter(nodes.toSet))
  }

  private def getOuter(sinks: Set[String]): Set[String] = {
    val nodes = N2E.filterKeys(sinks.contains).flatMap{
      case (s, e) => e match {
        case Mux(c, _, _, _) => Node.findNames(c)
        case DoPrim(op, args, _, _) if op.serialize == "eq" => args.flatMap(Node.findNames)
        case _ => Seq()
      }
    }.toSet

    nodes -- sinks
  }

  private def findSinks(source: String): Seq[String] = {
    assert(expG.keySet.contains(source),
      s"G does not contain $source")

    if (visited.contains(source))
      return Seq()

    val sinks = expG(source)

    visited.add(source)

    sinks.foldLeft(Seq(source))((list, str) =>
      Nodes(str).c match {
//        case "WDefInstance" | "DefMemory" => list
        case "DefInstance" | "DefMemory" => list
        case _ => list ++ findSinks(str)
      }
    )
  }

  def filterOut(nodes: Set[FirrtlNode]): Set[String] = {
    val outNames = nodes.map(n => n match {
      case reg: DefRegister => reg.name
      case mem: DefMemory => mem.name
      case _ => throw new Exception(s"${n.serialize} is not reg/mem")
    })

    Nodes.filter{
      case (s, n) => Seq("DefRegister", "DefMemory").contains(n.c)
    }.keySet.diff(outNames).toSet
  }
  
  def extract_ldq: Unit = {
    R.foreach {tup => 
      if (tup._1.contains("uop_ldq_idx")) {
      val parts = tup._1.split("uop_ldq_idx", 2)
      val parts1 = tup._1.split("bits_uop_ldq_idx", 2)
      val prefix = parts(0)
      val prefix1 = parts1(0)
      println(s"prefix is: ${prefix}, ${prefix1}")
      if (R.contains(prefix + "valid"))
        ldq_map(tup._1) = prefix + "valid"
      if (R.contains(prefix1 + "valid"))
        ldq_map(tup._1) = prefix1 + "valid"
    }
      if (tup._1.contains("valid")) {
        val prefix = tup._1.split("valid", 2)(0)
        uops_map(tup._1) = mutable.Set() ++ R.keySet.filter(str => str.contains(prefix+"bits_uop")) ++ R.filterKeys(_.contains(prefix + "uop")).keySet
      } 
    }
  }

  /******************** Print functions ******************************************/
  def printLog: Unit = {
    println(s"====================$mName=========================")

    println(s"---------${mName} R---------")
    R.foreach(tup => println(s"[${tup._1}] -- {${tup._2.mkString(", ")}}"))
    println("")

    println(s"---------${mName} ER---------")
    println(s"number: ${ER.size}")
    // ER.foreach(tup => println(s"[${tup._1}] -- {${tup._2.foreach(src=>" (" + src.mkString(", ") + ") ")}}"))
    ER.foreach(tup => {
      // Extract the key and value from the tuple
      val key = tup._1
      val value = tup._2

      // Use string interpolation to print the key and value
      println(s"[${key}] -- ${tup._2.size} {")
      
      // Iterate over the inner set of strings and print each set surrounded by parentheses
      value.foreach{case(expr, src) => println(s"${expr.serialize} => ${src.mkString(", ")}")}
      
      // Close the curly brace for the value and the square bracket for the key
      println("}")
    })
    println("")

    println(s"---------${mName} LOOPER---------")
    // ER.foreach(tup => println(s"[${tup._1}] -- {${tup._2.foreach(src=>" (" + src.mkString(", ") + ") ")}}"))
    println(s"number: ${LoopER.size}")
    LoopER.foreach(tup => {
      // Extract the key and value from the tuple
      val key = tup._1
      val value = tup._2

      // Use string interpolation to print the key and value
      println(s"[${key}] -- ${tup._2.size} {")
      
      // Iterate over the inner set of strings and print each set surrounded by parentheses
      value.foreach{case(expr, src) => println(s"${expr.serialize} => ${src.mkString(", ")}")}
      
      // Close the curly brace for the value and the square bracket for the key
      println("}")
    })
    println("")

//    println(s"---------${mName} input---------")
//    println(s"size: ${inPorts.size} [${inPorts.map(tup => tup.name).mkString(", ")}]}")
//    println("")
//
//    println(s"---------${mName} output---------")
//    println(s"size: ${outPorts.size} [${outPorts.map(tup => tup.name).mkString(", ")}]}")
//    println("")

    println(s"---------${mName} DEP---------")
    dep_map.foreach(tup => println(s"[${tup._1}] ${tup._2.size} -- {${tup._2.map(x => s"(${x._1}: ${x._2})").mkString("\n")}}"))
    println("")

    println(s"---------${mName} EDEP---------")
    Edep_map.foreach(tup => println(s"[${tup._1}] ${tup._2.size} -- {${tup._2.map(x => s"(${x._1}: ${x._2})").mkString("\n")}}"))
    println("")

    println(s"---------${mName} ISUNROLL---------")
    println(s"${ISigER.size}")
    ISigER.foreach(tup => println(s"[${tup._1}] -- {${tup._2.map{case(expr, srcs) => s"(${expr}: ${srcs.size} \n ${srcs.mkString(", ")})"}}}"))
    println("")

    println(s"---------${mName} G---------")
    G.foreach(tup => println(s"[${tup._1}] -- {${tup._2.mkString(", ")}}"))
    println("")

    println(s"---------${mName} EG---------")
    EG.foreach(tup => println(s"[${tup._1}] -- {${tup._2.map(x => s"(${x._1}, ${x._2.serialize})")}}"))
    println("")

    println(s"---------${mName} expEG---------")
    expEG.foreach(tup => println(s"[${tup._1}] -- {${tup._2.mkString(", ")}}"))
    println("")

    println(s"---------${mName} expG---------")
    expG.foreach(tup => println(s"[${tup._1}] -- {${tup._2.mkString(", ")}}"))
    println("")

    println(s"---------${mName} rN2IP---------")
    rN2IP.foreach(tup => println(s"[${tup._1}] -- {${tup._2.map(x => s"(${x._1}, ${x._2})").mkString(", ")}}"))
    println("")

    println(s"---------${mName} rIP2E---------")
    rIP2E.foreach(tup => println(s"${tup._1} -- {${tup._2.serialize}}"))
    println("")

    println(s"---------${mName} rN2MP---------")
    rN2MP.foreach(tup => println(s"[${tup._1}] -- {${tup._2.map(x => s"(${x._1}, ${x._2})").mkString(", ")}}"))
    println("")

    println(s"---------${mName} rMP2N---------")
    rMP2N.foreach{ case (k, v) => println(s"[(${k._1}, ${k._2})] -- {${v.mkString(", ")}}")}
    println("")
  }

  def getStat: (Int, Int) = {
    val reg = Nodes.count{ case (_, n) => n.c == "DefRegister" }
    val mem = Nodes.count{ case (_, n) => n.c == "DefMemory" }

    (reg, mem)
  }
}


