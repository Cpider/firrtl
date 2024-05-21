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

import scala.collection.mutable.ListBuffer
import scala.collection.Map
import scala.reflect.ClassTag

/* Saving module information */
object moduleInfo {
  def apply(gLedger: graphLedger): moduleInfo = {
    new moduleInfo(gLedger.mName, gLedger)
  }

  def findModules(gLedgers: Map[String, graphLedger], top: String, module: String): Int = {
    if (top == module) 1
    else {
      gLedgers(top).getNodes[WDefInstance].foldLeft(0)(
        (num, inst) => num + findModules(gLedgers, inst.module, module)
      )
    }
  }
}

class moduleInfo(val mName: String,
                 val gLedger: graphLedger) {

  var vPortSrcs = Map[String, Set[Node]]()
  var fInstPorts = Map[WDefInstance, Set[String]]()

  def getSrcNode[T <: FirrtlNode : ClassTag]: Set[T] = {
    vPortSrcs.getOrElse(
      implicitly[ClassTag[T]].toString.split("\\.").last, Set()
    ).map(_.node.asInstanceOf[T])
  }
  def getAllNodes: Set[String] = vPortSrcs.filterKeys(
    Set("DefNode", "DefWire", "DefRegister"
  ).contains(_)).values.flatten.map(_.name).toSet

  def getMemory: Set[(String, DefMemory)] = Stream.continually(mName).zip(getSrcNode[DefMemory]).toSet
  def getRegister: Set[(String, DefRegister)] = Stream.continually(mName).zip(getSrcNode[DefRegister]).toSet

  def getNumNodes: Int = getSrcNode[Port].size + fInstPorts.values.flatten.size

  def phase0(ports: Set[String]): Unit = {
    val (ret0, ret1) = gLedger.findPortSrcs(ports)
    vPortSrcs = ret0
    fInstPorts = ret1
  }

  def phase1(exprs: Set[Expression]): Unit = {
    val (ret0, ret1) = gLedger.findExprSrcs(exprs)
    vPortSrcs = ret0
    fInstPorts = ret1
  }

  def getPorts(mInfos: Map[String, moduleInfo]): ListBuffer[String] = {
    val parents = mInfos.values.filter(_.getSrcNode[WDefInstance].map(_.module).contains(mName))

    parents.flatMap(
      _.fInstPorts.filterKeys(_.module == mName).values.flatten
    ).to[ListBuffer]
  }

  def getInstCons(mInfos: Map[String, moduleInfo]): Set[Expression] = {
    val instModules = gLedger.getNodes[WDefInstance].map(wdi => (wdi.name, wdi.module)).toMap
    val instMInfos = instModules.map{ case (k, v) => (k, mInfos(v)) }

    val instPorts = instMInfos.toSeq.flatMap {
      case (inst, mInfo) => Stream.continually(inst).zip(mInfo.getSrcNode[Port].map(_.name))
    }.toSet

    gLedger.IP2E.filterKeys(instPorts.contains).values.toSet
  }

  def printInfo: Unit = {
    println(s"------------[${mName}]------------")
    vPortSrcs.foreach(tup => println(s"${tup._1}: {${tup._2.map(_.name).mkString(", ")}}"))
    println("")
    fInstPorts.foreach(tup => println(s"[${tup._1.name}] -- {${tup._2.mkString(", ")}}"))
    println("-----------------------------------")
  }
}


/* Module instantiation network */
object moduleNet {
  def apply(gLedgers: Map[String, graphLedger], topModuleName: String): moduleNet = {
    val nodeInsts = gLedgers.toSeq.map(tup => (netNode(tup._1, Seq(), Seq()), tup._2.getNodes[WDefInstance]))

    for ((node, insts) <- nodeInsts) {
      node.childs = nodeInsts.map(_._1).filter(x => insts.map(_.module).contains(x.n))
    }

    for (node <- nodeInsts.map(_._1)) {
      node.parents = nodeInsts.map(_._1).filter(x => x.childs.exists(_.n == node.n))
    }

    new moduleNet(nodeInsts.map(_._1))
  }
}

class moduleNet(val nodes: Seq[netNode]) {
  var numNodes = 0
  var roots = ListBuffer[netNode]()
  var leaves = ListBuffer[netNode]()

  def reset: Unit = {
    numNodes = nodes.size
    nodes.foreach(_.reset)

    roots = nodes.filter(_.parents.isEmpty).to[ListBuffer]
    leaves = nodes.filter(_.childs.isEmpty).to[ListBuffer]
  }

  /* Pop topmost module name and re-sort network */
  def popT: Option[String] = {
    /* Module hierarchy must not form a loop */
    assert(numNodes >= 0, "Incorrect moduleNet")

    if (roots.isEmpty) None
    else {
      val root = roots.remove(0)
      numNodes = numNodes - 1

      for (node <- root.childs) {
        node.parents = node.parents.filterNot(_ == root)
        if (node.parents.isEmpty) roots.append(node)
      }

      Some(root.n)
    }
  }

  /* Pop bottommost module name and re-sort network */
  def popB: Option[String] = {
    /* Module hierarchy must not form a loop */
    assert(numNodes >= 0, "Incorrect moduleNet")

    if (leaves.isEmpty) None
    else {
      val leaf = leaves.remove(0)
      numNodes = numNodes - 1

      for (node <- leaf.parents) {
        node.childs = node.childs.filterNot(_ == leaf)
        if (node.childs.isEmpty) leaves.append(node)
      }

      Some(leaf.n)
    }
  }

  /*******************Print Function*****************8**/
  def printNet(dir: Boolean=true): Unit = {
    reset

    val pop = () => if (dir) popT else popB
    var done = false
    while (!done) {
      pop() match {
        case Some(nName) => println(s"$nName")
        case None => done = true
      }
    }
  }
}

case class netNode(n: String, var parents: Seq[netNode], var childs: Seq[netNode]) {
  private lazy val immParents = parents
  private lazy val immChilds = childs

  def reset: Unit = {
    parents = immParents
    childs = immChilds
  }
}


