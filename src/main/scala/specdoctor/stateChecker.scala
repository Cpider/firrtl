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

import firrtl.PrimOps._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.math._
import scala.util.Random

class StateChecker(outerName: String, topModuleName: String, modules: Set[String], hashSz: Int = 64) {
  var mName: String = ""
  var mID = 0
  var iID = 0

  var clock: WRef = _
  var reset: Expression = _
  var idRef: Expression = _

  def instrument(m: DefModule, atkMems: Set[DefMemory], atkRegs: Map[String, Set[DefRegister]]):
      DefModule = {
    mName = m.name
    iID = 0

    m match {
      case mod: Module if modules.contains(mName) =>
        val hasCNR = hasClockAndReset(mod)
        clock = WRef(hasCNR._1.get)

        val on = Port(NoInfo, "io_spdoc_check", Input, uTp(1))
        val done = Port(NoInfo, "io_spdoc_done", Output, uTp(1))

        /* on pulse to reset hash, cnt register */
        val (onDelay, _) = defReg(s"${mName}_spdoc_reset", clock, 1)
        val onDelayCon = Connect(NoInfo, WRef(onDelay),
          DoPrim(Not, Seq(WRef(on)), Seq(), uTp(1)))

        reset = DoPrim(And, Seq(WRef(on), WRef(onDelay)), Seq(), uTp(1))

        /* Instrument instantiation specific ID */
        val (id, myId) = getID(mID)
        mID += 1

        idRef = WRef(myId)

        val dones = ListBuffer[Statement]()

        /* Connect specdoctor ports */
        var newMod = mod map conPorts(WRef(on), dones)

        /* Instrument hash logic detected memories and registers */
        newMod = newMod map instrMem(atkMems, WRef(on), dones)
        val regCons = instrRegs(atkRegs, reset)

        /* Wire out spdoc_done ports */
        val (doneWires, doneRef) = connectDone(dones, done)

        val body = newMod.asInstanceOf[Module].body
        Module(mod.info, mName,
          mod.ports ++ id :+ on :+ done,
          Block(Seq(body, onDelay, onDelayCon, myId) ++ regCons ++ dones ++ doneWires))

      /* Connect TopLevel module to the outer module */
      case outer: Module if mName == outerName =>
        val on = outer.ports.find(_.name == "spdoc_check").getOrElse(
          throw new Exception(s"${mName} does not have spdoc_check")
        )
        val done = outer.ports.find(_.name == "spdoc_done").getOrElse(
          throw new Exception(s"${mName} does not have spdoc_done")
        )

        val topInsts = ListBuffer[WDefInstance]()
        outer foreachStmt findTopInst(topInsts)
        val topInst = topInsts.length match {
          case 1 => topInsts.head
          case 0 => throw new Exception(s"${mName} do not have ${topModuleName}")
          case _ => throw new Exception(s"${mName} have multiple ${topModuleName}")
        }

        val newMod = outer map connectSpdoc(on, done, topInst)
        Module(outer.info, mName, outer.ports,
          Block(Seq(newMod.asInstanceOf[Module].body)))

      case other => other
    }
  }

  private def connectDone(dones: Seq[Statement], done: Port): (Seq[Statement], WRef) = {
    var i = 0
    val doneAnds = dones.foldLeft(
      Seq(DefNode(NoInfo, s"${mName}_done_and${i}", uLit(1, 1)))
    )((n, d) => {
      i += 1
      val ref = d match {
        case w: DefWire => WRef(w)
        case n: DefNode => WRef(n)
        case _ => throw new Exception(s"${d} is not DefWire/DefNode")
      }
      n :+ DefNode(NoInfo, s"${mName}_done_and${i}",
        DoPrim(And, Seq(WRef(n.last), ref), Seq(), uTp(1)))
    })

    val doneCon = Connect(NoInfo, WRef(done), WRef(doneAnds.last))

    (doneAnds :+ doneCon, WRef(doneAnds.last))
  }

  private def getID(moduleID: Int): (Seq[Port], DefNode) = {
    val id = mName match {
      case `topModuleName` => None
      case _ => Some(Port(NoInfo, "io_mid", Input, uTp(8)))
    }

    val myId = DefNode(NoInfo, s"${mName}_mid", id.map(i =>
    DoPrim(Add, Seq(WRef(i), uLit(moduleID, 8)), Seq(), uTp(8))
    ).getOrElse(uLit(moduleID, 8)))

    (id.map(Seq(_)).getOrElse(Nil), myId)
  }

  /***************** Port connections *****************/

  private def conPorts(on: Expression, dones: ListBuffer[Statement])
                      (stmt: Statement): Statement = {
    stmt match {
      case inst: WDefInstance if modules.contains(inst.module) =>
        iID += 1
        dones.append(DefNode(NoInfo, s"${inst.name}_done", WSubField(WRef(inst), "io_spdoc_done", uTp(1))))
        Block(Seq(inst,
          Connect(NoInfo, WSubField(WRef(inst), "io_mid"), DoPrim(Xor, Seq(idRef, uLit(iID, 8)), Seq(), uTp(8))),
          Connect(NoInfo, WSubField(WRef(inst), "io_spdoc_check"), on),
        ))
      case o => o map conPorts(on, dones)
    }
  }

  /***************** Attackable memories instrumentation *****************/

  private def instrMem(atkMems: Set[DefMemory], on: Expression, dones: ListBuffer[Statement])
              (stmt: Statement): Statement = {

    stmt match {
      case mem: DefMemory if atkMems.contains(mem) =>
        val done = DefWire(NoInfo, s"${mem.name}_done", uTp(1))
        dones.append(done)
        Block(hashMem(mem, on, WRef(done)))
      case o => o map instrMem(atkMems, on, dones)
    }
  }

  private def hashMem(mem: DefMemory, on: Expression, done: Expression):
      Seq[Statement] = {
    mem.dataType match {
      case UIntType(IntWidth(w)) =>
        val d = mem.depth
        val dBits = (log(d.toInt + 1)/log(2)).ceil.toInt

        val newMem = mem.copy(readers = mem.readers :+ "spdoc")
        val (hashReg, hashRegRst) = defReg(s"${mem.name}_hash", clock, hashSz, Some(reset))
        val (cntReg, cntRegRst) = defReg(s"${mem.name}_cnt", clock, dBits, Some(reset))

        val (memConStmts, dataRef) = memCon(newMem, WRef(cntReg), uLit(1, 1), w)

        val (cLogic, cntOn, cntMux) = counterLogic(WRef(cntReg), on, d, dBits)
        val (hLogic, hashMux) = hashLogic(WRef(hashReg), dataRef, cntOn)

        /* Embed reset logic */
        val cntCon = cntRegRst.get(cntMux)
        val hashCon = hashRegRst.get(hashMux)

        val (doneStmts, doneReg, doneSig) = donePulse(WRef(cntReg), d)

        Seq(newMem, hashReg, cntReg) ++ /* newMem & registers for hashing */
          memConStmts ++ /* Memory port connections & data trimmed to hashSz */
          cLogic ++ hLogic ++ Seq(cntCon, hashCon) ++ /* Connecting hashReg */
          doneStmts :+ /* For printing hash value */
          Connect(NoInfo, done, doneReg) :+ /* Wire out done signal */
          Print(NoInfo, StringLit(s"[${mName}(%d)](${mem.name})=[%h]\n"), Seq(idRef, WRef(hashReg)), clock, doneSig)
      case _ =>
        throw new Exception(s"${mem.name} has type: ${mem.dataType}")
    }
  }

  /* Connect memory ports and return data field trimmed to the hashSz */
  private def memCon(mem: DefMemory, addr: Expression, en: Expression, w: BigInt):
      (Seq[Statement], WRef) = {
    val memReadSF = WSubField(WRef(mem), "spdoc")

    val addrW = (log(mem.depth.toInt)/log(2)).ceil.toInt
    val readAddr = Connect(NoInfo, WSubField(memReadSF, "addr"),
      DoPrim(Bits, Seq(addr), Seq(addrW - 1, 0), uTp(addrW)))
    val readEn = Connect(NoInfo, WSubField(memReadSF, "en"), en)
    val readClk = Connect(NoInfo, WSubField(memReadSF, "clk"), clock)

    val name = memReadSF.expr.serialize

    val dataNode = DefNode(NoInfo, s"${name}_node", WSubField(memReadSF, "data"))
    val cutNodes = (0 until w.toInt by hashSz).map(
      i => (i/hashSz,
        if (w.toInt > i + hashSz)
          DoPrim(Bits, Seq(WRef(dataNode)), Seq(i + hashSz - 1, i), uTp(hashSz))
        else
          DoPrim(Pad, Seq(DoPrim(Bits, Seq(WRef(dataNode)), Seq(w.toInt - 1, i), uTp(w.toInt - i))),
            Seq(hashSz), uTp(hashSz))
      )
    ).map{ case (i, e) => DefNode(NoInfo, s"${name}_cut${i}", e) }

    val mergeNodes = cutNodes.size match {
      case 1 =>
        Seq(DefNode(NoInfo, s"${name}_merge0", WRef(cutNodes.head)))
      case _ =>
        cutNodes.zipWithIndex.foldLeft(
          Seq(DefNode(NoInfo, s"${name}_merge0", uLit(0, hashSz)))
        )((s, n) => s :+ DefNode(NoInfo, s"${name}_merge${n._2 + 1}",
          DoPrim(Xor, Seq(WRef(s.last), WRef(n._1)), Seq(), uTp(hashSz))
        ))
    }

    (Seq(readAddr, readEn, readClk, dataNode) ++ cutNodes ++ mergeNodes, WRef(mergeNodes.last))
  }

  /* Address selection logic
  When io_spdoc_check & (cntReg < d), increment counter register
  */
  private def counterLogic(cntReg: WRef, on: Expression, d: BigInt, dBits: Int):
      (Seq[Statement], WRef, Mux) = {

    val cntIng = DefNode(NoInfo, s"${cntReg.serialize}_not_done",
      DoPrim(Neq, Seq(cntReg, uLit(d)), Seq(), uTp(1)))
    val cntOn = DefNode(NoInfo, s"${cntReg.serialize}_on",
      DoPrim(And, Seq(on, WRef(cntIng)), Seq(), uTp(1)))
    val cntMux = Mux(WRef(cntOn),
      DoPrim(Add, Seq(cntReg, uLit(1, dBits)), Seq(), uTp(dBits)),
      cntReg)

    (Seq(cntIng, cntOn), WRef(cntOn), cntMux)
  }

  /* Hash logic
  While increasing counter register, hash the corresponding memory line into the hash register.
  To increase entropy, shift left the input value each time.
  */
  private def hashLogic(hashReg: WRef, dataRef: WRef, cntOn: WRef):
      (Seq[Statement], Mux) = {

    val unit = Random.nextInt(hashSz - 2) + 1

    val shlNode = DefNode(NoInfo, s"${hashReg.serialize}_shl",
      DoPrim(Shl, Seq(
        DoPrim(Bits, Seq(hashReg), Seq(hashSz - unit - 1, 0), uTp(hashSz - unit))
      ), Seq(unit), uTp(hashSz)))
    val shrNode = DefNode(NoInfo, s"${hashReg.serialize}_shr",
      DoPrim(Cat, Seq(uLit(0, hashSz - unit),
        DoPrim(Shr, Seq(hashReg), Seq(hashSz - unit), uTp(hashSz - unit))
      ), Seq(), uTp(hashSz)))
    val shNode = DefNode(NoInfo, s"${hashReg.serialize}_sh",
      DoPrim(Xor, Seq(WRef(shlNode), WRef(shrNode)), Seq(), uTp(hashSz)))

    val hashMux = Mux(cntOn,
      DoPrim(Xor, Seq(WRef(shNode), dataRef), Seq(), uTp(hashSz)),
      hashReg)

    (Seq(shlNode, shrNode, shNode), hashMux)
  }

  private def donePulse(cntReg: WRef, d: BigInt): (Seq[Statement], Expression, Expression) = {
    val done = DefNode(NoInfo, s"${cntReg.serialize}_done",
      DoPrim(Eq, Seq(cntReg, uLit(d)), Seq(), uTp(1)))
    val (done_d, _) = defReg(s"${done.name}_d", clock, 1)
    val doneCon = Connect(NoInfo, WRef(done_d), WRef(done))

    (Seq(done, done_d, doneCon),
      WRef(done_d),
      DoPrim(Xor, Seq(WRef(done), WRef(done_d)), Seq(), uTp(1)))
  }

  /***************** Attackable registers instrumentation *****************/

  private def instrRegs(atkRegs: Map[String, Set[DefRegister]], on: Expression):
      Seq[Statement] = {

    val sAtkRegs = atkRegs.filter{ case (pfx, s) =>
      if (s.exists(r => width(r) > hashSz))
        println(s"[$mName] ${pfx}(${s.map(_.name).mkString(", ")}) contains too large register")
      s.forall(r => width(r) <= hashSz)
    }

    // assert(atkRegs.flatMap(_._2).forall(r => width(r) <= hashSz),
    //        s"${atkRegs.flatMap(_._2.filter(r => width(r) > hashSz).map(_.name)).mkString(", ")} has width > ${hashSz}")

    val padRes = sAtkRegs.map{
      case (pfx, regs) =>
        val regOffs = getOffset[DefRegister](regs.toSeq, hashSz)
        (pfx -> makeShift[DefRegister](regOffs, hashSz))
    }

    val padRefs = padRes.mapValues(_._1)
    val padStmts = padRes.values.flatMap(_._2)

    val xorRes = padRefs.map{case (pfx, regs) =>
      (pfx -> makeXor(pfx, regs, 0, hashSz))
    }

    val xorStmts = xorRes.values.flatMap(_._2)
    val topXors = xorRes.mapValues(_._1)

    // TODO: Print logic when on signal is asserted
    val prints = topXors.map{case (pfx, xor) =>
      Print(NoInfo, StringLit(s"[${mName}(%d)](${pfx.substring(0, pfx.length - 1)})=[%h]\n"),
      Seq(idRef, xor), clock, on)
    }

    (padStmts ++ xorStmts ++ prints).toSeq
  }

  private def getOffset[T <: FirrtlNode : ClassTag](stmts: Seq[T], size: Int):
      Seq[(T, Int)] = {

    val widthSeq = stmts.map(s => width(s))
    val totBitWidth = widthSeq.sum
    val zipWidth = stmts zip widthSeq

    totBitWidth match {
      case 0 => Seq[(T, Int)]()
      case x if x <= size => {
        var sum_offset = 0
        zipWidth.map(tuple => {
          val offset = sum_offset
          sum_offset = sum_offset + tuple._2
          (tuple._1, offset)
        }).toSeq
      }
      case x => {
        val rand = Random
        zipWidth.map { case (x, i) =>
          (x, rand.nextInt(size - i + 1))
        }
      }
    }
  }

  private def makeShift[T <: FirrtlNode : ClassTag](stOffs: Seq[(T, Int)], size: Int):
      (Seq[WRef], Seq[Statement]) = {

    val stmts = stOffs.map{ case (s, o) =>
      val w = width(s)
      val p = size - w - o

      val shl = DefNode(NoInfo, s"${name(s)}_shl",
        DoPrim(Shl, Seq(wref(s)), Seq(o), uTp(w + o)))
      val padVal = p match {
        case 0 => WRef(shl)
        case _ => DoPrim(Cat, Seq(uLit(0, p), WRef(shl)), Seq(), uTp(size))
      }
      val pad = DefNode(NoInfo, s"${name(s)}_pad", padVal)

      Seq(shl, pad)
    }

    (stmts.map(s => WRef(s.last)), stmts.flatten)
  }

  private def makeXor(name: String, refs: Seq[WRef], i: Int, size: Int):
      (WRef, Seq[Statement]) = {
    refs.size match {
      case 1 => (refs.head, Seq[Statement]())
      case 2 => {
        val xor_wire = DefWire(NoInfo, name + s"_xor${i}", uTp(size))
        val xor_op = DoPrim(Xor, refs, Seq(), uTp(size))
        val xor_con = Connect(NoInfo, WRef(xor_wire), xor_op)
        (WRef(xor_wire), Seq(xor_wire, xor_con))
      }
      case _ => {
        val (xor1, stmts1) = makeXor(name, refs.splitAt(refs.size / 2)._1, 2 * i + 1, size)
        val (xor2, stmts2) = makeXor(name, refs.splitAt(refs.size / 2)._2, 2 * i + 2, size)
        val xor_wire = DefWire(NoInfo, name + s"_xor${i}", uTp(size))
        val xor_op = DoPrim(Xor, Seq(xor1, xor2), Seq(), uTp(size))
        val xor_con = Connect(NoInfo, WRef(xor_wire), xor_op)
        (WRef(xor_wire), stmts1 ++ stmts2 :+ xor_wire :+ xor_con)
      }
    }
  }

  private def hasClockAndReset(mod: Module): (Option[Port], Option[Port], Boolean) = {
    val ports = mod.ports
    val (clk, rst) = ports.foldLeft[(Option[Port], Option[Port])]((None, None))(
      (tuple, p) => {
        if (p.name == "clock" || p.name == "gated_clock") (Some(p), tuple._2)
        else if (p.name == "reset") (tuple._1, Some(p))
        else tuple
      })
    val hasCNR = clk.isDefined && rst.isDefined

    (clk, rst, hasCNR)
  }

  private def findTopInst(insts: ListBuffer[WDefInstance])(stmt: Statement): Unit = {
    stmt match {
      case inst: WDefInstance if inst.module == topModuleName =>
        insts.append(inst)
      case o => o foreachStmt findTopInst(insts)
    }
  }

  private def connectSpdoc(on: Port, done: Port, topInst: WDefInstance)(stmt: Statement): Statement = {
    stmt match {
      case c: Connect if c.loc.serialize.contains(done.name) =>
        Block(Seq(
          Connect(NoInfo, WSubField(WRef(topInst), "io_spdoc_check"), WRef(on)),
          Connect(NoInfo, WRef(done), WSubField(WRef(topInst), "io_spdoc_done"))
        ))
      case o => o map connectSpdoc(on, done, topInst)
    }
  }

  /***************** Syntactic Sugar *****************/

  private def defReg(name: String, clk: Expression, width: Int, rst: Option[Expression] = None,
                     init: Int = 0): (DefRegister, Option[(Expression) => Statement]) = {
    val initReg = (name: String, width: Int) =>
      WRef(name, uTp(width), RegKind, UnknownFlow)

    val reg = DefRegister(NoInfo, name, uTp(width), clk, uLit(0, 1), initReg(name, width))
    val resetCon = rst.map(
      r => (v: Expression) =>Connect(NoInfo, WRef(reg), Mux(r, uLit(init, width), v))
    )

    (reg, resetCon)
  }

  private def wref(node: FirrtlNode): WRef = {
    node match {
      case mem: DefMemory => WRef(mem)
      case wi: WDefInstance => WRef(wi)
      case port: Port => WRef(port)
      case node: DefNode => WRef(node)
      case reg: DefRegister => WRef(reg)
      case wire: DefWire => WRef(wire)
      case _ =>
        throw new Exception(s"wref not supported on ${node.serialize}")
    }
  }

  private def width(node: FirrtlNode): Int = {
    val tpe = node match {
      case r: DefRegister => r.tpe
      case w: DefWire => w.tpe
      case p: Port => p.tpe
      case _ =>
        throw new Exception(s"width not supported on ${node.serialize}")
    }
    val width = tpe match {
      case UIntType(iw) => iw
      case SIntType(iw) => iw
      case _ => throw new Exception("${tpe.serialize} doesn't have width")
    }
    width match {
      case IntWidth(len) => len.toInt
      case _ => throw new Exception("${tpe.serialize} width not IntWidth")
    }
  }

  private def name(node: FirrtlNode): String = {
    node match {
      case r: DefRegister => r.name
      case w: DefWire => w.name
      case p: Port => p.name
      case _ =>
        throw new Exception(s"name not supported on ${node.serialize}")
    }
  }


  private def uTp(w: BigInt): UIntType =
    UIntType(IntWidth(w))

  private def uLit(v: BigInt, w: BigInt = 0): UIntLiteral =
    if (w == 0) UIntLiteral(v) else UIntLiteral(v, IntWidth(w))

}

