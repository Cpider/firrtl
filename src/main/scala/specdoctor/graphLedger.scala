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

  private val Nodes = mutable.Map[String, Node]()
  private val G = mutable.Map[String, Set[String]]()
  val R = mutable.Map[String, Set[String]]()
  private val expG = mutable.Map[String, Set[String]]()

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
      this.module foreachStmt findEdge(Nodes(n), sinks)
      G(n) = sinks.toSet

      expG(n) = Set[String]()
    }

    for ((n, _) <- expG) {
      val sinks = ListBuffer[String]()
      this.module foreachStmt findEdgeExp(Nodes(n), sinks)
      expG(n) = sinks.toSet
    }
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
    }

    s match {
      case stmt: Statement =>
        stmt foreachStmt findNode
      case other => Unit
    }
  }

  private def findEdgeExp(n: Node, sinks: ListBuffer[String])(s: Statement): Unit = {
    s match {
      case reg: DefRegister if (n.usedIn(reg.reset, false)) =>
        sinks.append(reg.name)
      case nod: DefNode if (n.usedIn(nod.value, false)) =>
        sinks.append(nod.name)
      case Connect(_, l, e) if (n.usedIn(e, false)) =>
        sinks.append(Node.findName(l))
      case _ => Unit
    }

    s foreachStmt findEdgeExp(n, sinks)
  }

  //noinspection ScalaStyle
  /* Find explicit data flow edges */
  private def findEdge(n: Node, sinks: ListBuffer[String])(s: Statement): Unit = {
    s match {
      case reg: DefRegister =>
        if (n.usedIn(reg.reset)) {
          sinks.append(reg.name)
        }
      case nod: DefNode =>
        if (n.usedIn(nod.value)) {
          sinks.append(nod.name)

          updateN2XP(nod.name, nod.value, n)
        }
        updateN2E(nod.name, nod.value)
      case Connect(_, l, e) =>
        val lName = Node.findName(l)
        if (n.usedIn(e)) {
          sinks.append(lName)

          updateN2XP(lName, e, n)
          updateXP2X(lName, l, e, n)
          // updateP2E(lName, e)
        }
        updateN2E(lName, e)
      case _ => Unit // Port, DefWire, DefMemory, WDefInstance
    }

    s foreachStmt findEdge(n, sinks)
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

  /******************** Print functions ******************************************/
  def printLog: Unit = {
    println(s"====================$mName=========================")

    println("---------R---------")
    R.foreach(tup => println(s"[${tup._1}] -- {${tup._2.mkString(", ")}}"))
    println("")

    println("---------rN2IP---------")
    rN2IP.foreach(tup => println(s"[${tup._1}] -- {${tup._2.map(x => s"(${x._1}, ${x._2})").mkString(", ")}}"))
    println("")

    println("---------rIP2E---------")
    rIP2E.foreach(tup => println(s"${tup._1} -- {${tup._2.serialize}}"))
    println("")

    println("---------rN2MP---------")
    rN2MP.foreach(tup => println(s"[${tup._1}] -- {${tup._2.map(x => s"(${x._1}, ${x._2})").mkString(", ")}}"))
    println("")

    println("---------rMP2N---------")
    rMP2N.foreach{ case (k, v) => println(s"[(${k._1}, ${k._2})] -- {${v.mkString(", ")}}")}
    println("")
  }

  def getStat: (Int, Int) = {
    val reg = Nodes.count{ case (_, n) => n.c == "DefRegister" }
    val mem = Nodes.count{ case (_, n) => n.c == "DefMemory" }

    (reg, mem)
  }
}


