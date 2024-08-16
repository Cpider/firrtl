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
import firrtl.Mappers._

import java.io.{File, PrintWriter}
import org.json4s._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.writePretty
import scala.collection.mutable

/*
Detect and instrument u-arch side channel attackable RTL components
Work flow:
  1) Find all module hierarchy by doing light-weight static analysis
  2) Find all 'valid-signal' related components in toplevel RTL module
  3) Recursively find all related components across the module boundary
*/
class SpdocInstr extends Transform {

  def inputForm: LowForm.type = LowForm
  def outputForm: LowForm.type = LowForm

  val parsed_ins_type = mutable.Map[String, mutable.Set[String]]()
  val input_io: mutable.Set[String] = mutable.Set()

  def printd(str: String, d: Boolean): Unit = if (d) println(str)

  def execute(state: CircuitState): CircuitState = {
    val circuit = state.circuit
    val modules = circuit.modules.map(_.name)

    if (sys.env.contains("NOSPDOC")) {
      return state
    }
    /* Parse compile options (debug, topModule) */
    val debug = sys.env.get("DEBUG") match { case Some(x) if x == "1" => true; case _ => false }
    val topModuleName = sys.env.get("TOPMODULE") match {
      case Some(m) => m
      case None =>
        println(s"Set TOPMODULE=[${circuit.main}]")
        circuit.main
    }
    val topModule = modules.find(_ == topModuleName).getOrElse(
      throw new Exception(s"${topModuleName} does not exist")
    )

    println("* Finding Attackable Memories ...")
    val gLedgers = circuit.modules.map(m => (m.name, new graphLedger(m))).toMap
    gLedgers.foreach(_._2.parse)
    val mInfos = gLedgers.map(tup => (tup._1, moduleInfo(tup._2)))

    printd("** Building 1-level Instance Map ...", debug)
    val fInsts = Set("frontend", "core", "ptw", "lsu", "dcache")
    // getInstanceMap can get the instance module internal its module, not include the module type.
    val instMap = gLedgers(topModuleName).getInstanceMap.filterKeys(fInsts.contains)

    // println(s"instMap is: ${instMap}")

    ////////////////////////////////////////////////////////////////////////////////
    //           Finding Attackable Components                                    //
    ////////////////////////////////////////////////////////////////////////////////

    val atkMems = mutable.Set[(String, DefMemory)]()
    val atkRegs = mutable.Set[(String, DefRegister)]()
    for ((_, v) <- instMap) {
      val module = v._1
      // val module = "BoomNonBlockingDCache"
      val ledgers = gLedgers.filterKeys(moduleInfo.findModules(gLedgers, module, _) > 0)

      val mNet = moduleNet(ledgers, module)

      // println(s"module $module mNet is: ${mNet.printNet(true)}")

      var done = false
      var iter = 0

      val topPorts = v._2.flatMap(x => if(fInsts.contains(x._1)) x._2 else Set[String]())
      // val topPorts = Set("io_lsu_resp_0_valid", "io_lsu_nack_0_valid", "io_lsu_release_valid")
      while (!done && iter < 10) {
        if (iter == 0)
          phase0(mNet, mInfos, module, topPorts)
        else phase0(mNet, mInfos, module)

        atkMems ++= mInfos.values.map(_.getMemory).reduce(_ ++ _)
        atkRegs ++= mInfos.values.map(_.getRegister).reduce(_ ++ _)

        phase1(mNet, mInfos)
        println(s"module $module mNet is: ${mNet.printNet(true)}")

        atkMems ++= mInfos.values.map(_.getMemory).reduce(_ ++ _)
        atkRegs ++= mInfos.values.map(_.getRegister).reduce(_ ++ _)

        iter += 1
        done = (mInfos.values.map(_.getNumNodes).sum == 0)
      }
    }

    println("*** Detection Result ...")

    // println("[Non-attackable components]")
    // gLedgers.foreach{ case (mod, g) =>
    //   val atks = mutable.Set[FirrtlNode]()
    //   atks ++= atkMems.collect { case (m, mem) if m == mod => mem }
    //   atks ++= atkRegs.collect { case (m, reg) if m == mod => reg }
    //   println(s"${mod}: ${g.filterOut(atks.toSet).mkString(", ")}")
    // }

    atkMems --= atkMems.filter{ case (n, mem) => mem.depth <= 1 }

    println("* [Memory] *")
    val atkMemMap = gLedgers.map{ case (mod, g) =>
      mod -> atkMems.collect{ case (m, mem) if m == mod => mem }.toSet
    }.filter(_._2.size > 0)

    atkMemMap.foreach(x =>
        println(s"${x._1}: ${x._2.map(_.name).mkString(", ")}")
    )

    println("\n* [Register] *")
    val atkRegMap = gLedgers.map{ case (mod, g) =>
      val allRegs = atkRegs.collect{
        case (m, r) if m == mod && g.isStatePreserving(r) => r
      }.toSet
      val vecRegs = g.findVecRegs(allRegs)
      val scalaRegs = (allRegs.diff(vecRegs.flatMap(_._2).toSet)) match {
        case regs: Set[DefRegister] if regs.size == 0 => Map[String, Set[DefRegister]]()
        case regs => Map("remaining_" -> regs)
      }
      mod -> (vecRegs ++ scalaRegs)
    }.filter(_._2.size > 0)

    atkRegMap.foreach{case (m, map) =>
      val regs = map.keySet.map(r => r.substring(0, r.length - 1))
      println(s"$m: ${regs.mkString(", ")}")
    }

    ////////////////////////////////////////////////////////////////////////////////
    //           Instrumenting Attackable Components                              //
    ////////////////////////////////////////////////////////////////////////////////

    /* Find modules b/w TopModule and atkMem */
    val parentMs = modules.filter(m =>
      atkMems.map(a => moduleInfo.findModules(gLedgers, m, a._1)).sum > 0 ||
        atkRegMap.collect{
          case (k, v) if v.size > 0 => k
        }.map(moduleInfo.findModules(gLedgers, m, _)).sum > 0
    )

    val intmMs = (parentMs.filter(
      moduleInfo.findModules(gLedgers, _, topModuleName) == 0
    ) :+ topModuleName).toSet

    // val stChecker = new StateChecker("DigitalTop", topModuleName, intmMs)
    // val instrCircuit = circuit map {
    //   m: DefModule => {
    //     val mems = atkMemMap.getOrElse(m.name, Set())
    //     val regs = atkRegMap.getOrElse(m.name, Map())
    //     stChecker.instrument(m, mems, regs)
    //   }
    // }

    ////////////////////////////////////////////////////////////////////////////////
    //           Print Instrumentation Details                                    //
    ////////////////////////////////////////////////////////////////////////////////
    gLedgers.values.foreach(_.extract_ldq)
    gLedgers.values.foreach(gl =>{
      println(s"module is: ${gl.mName}}") 
      gl.ldq_map.foreach(tup => println(s"ldq map: ${tup._1} valid is: {${tup._2}}"))
      gl.uops_map.foreach(tup => println(s"uops map: ${tup._1} valid is: {${tup._2.mkString(", ")}}"))
    }
    
      )
    if (debug) gLedgers.values.foreach(_.printLog)
    if (debug) mInfos.values.foreach(_.printInfo)

    if (debug) {
      val rm = gLedgers.foldLeft((0, 0))((rm, g) =>
        (rm._1 + g._2.getStat._1, rm._2 + g._2.getStat._2)
      )
      val atk_rm = (atkRegs.size, atkMems.size)
      val atk_rm_opt = (atkRegMap.map{ case (m, rs) => (m, rs.values.flatten) }.values.flatten.size, atkMems.size)

      println("=============Instrumentation Statistics==============")
      println(s"Num. reg: ${rm._1}, ${atk_rm._1}, ${atk_rm_opt._1}")
      println(s"Num. mem: ${rm._2}, ${atk_rm._2}, ${atk_rm_opt._2}")
    }

    /* Get all the dependence valid signals */
    println("---------sensitive---------")
    // gLedgers.keys.foreach(println)
    val sensitive_signals = Map("LSU" -> mutable.Set("io_core_exe_0_iresp_valid"))
    // val dependence_signals = findAllValues(sensitive_signals.values.head, gLedgers.get(sensitive_signals.keys.head).get.R) 
    val not_parsed_module: mutable.Set[String] = mutable.Set[String]() ++ sensitive_signals.keys
    val parsed_module = mutable.Set[String]()
    val module_sigs: mutable.Map[String, mutable.Set[String]] = mutable.Map[String, mutable.Set[String]]() ++ sensitive_signals
    val dependence_tree: mutable.Map[String, mutable.Map[String, mutable.Set[String]]] = mutable.Map.empty[String, mutable.Map[String, mutable.Set[String]]]
    val tile_instance = gLedgers.get("BoomTile").get.getInstanceMap
    val connect_port = mutable.Map[(String, String), String]()
    // val parsed_ins_type = mutable.Map[String, mutable.Set[String]]()

    // map => (in module, input signal => outmodule.output signal) sink -> source
    val tile_port_connect = gLedgers.get("BoomTile").get.rIP2E.map {case((mod, io), expr)=> ((mod, io), expr.serialize)}
    val instance_type_map = tile_instance.map { case (key, (value, _)) => (key, value) }
    val type_instance_map = tile_instance.map { case (key, (value, _)) => (value, key) }

    while (!not_parsed_module.isEmpty) {
      val modulename: String = not_parsed_module.head
      // if (parsed_module.contains(modulename)) continue
      parsed_module += modulename
      not_parsed_module -= modulename
      val graphLedgerOption = gLedgers.get(modulename)
      if (graphLedgerOption.isDefined) {
        val graphLedger = graphLedgerOption.get
        val all_valid = graphLedger.R.keys.filter(_.contains("valid"))
        val all_io_valid = graphLedger.R.keys.filter(x => x.contains("valid") && x.contains("io"))

        println(s"all_valid is: ${all_valid} size is: ${all_valid.size}")
        println(s"all_io_valid is: ${all_io_valid} size is: ${all_io_valid.size}")

        // val dependence_signals = findAllValues(sensitive_signals.values.head, graphLedger).filter(x => !x.contains("_T") && !x.contains("_GEN"))
        val sensitive_io: mutable.Set[String] = mutable.Set.empty ++ module_sigs(modulename)
        val validIO = mutable.Map[String, mutable.Set[String]]()
        
        sensitive_io.foreach {sensitive_signal => 
          val (valid_io : mutable.Map[String, mutable.Set[String]], ins_io)  = findValidIO(sensitive_signal, mutable.Set.empty, mutable.Set.empty, mutable.Set(sensitive_signal), graphLedger)
          valid_io.foreach { case (key, value) =>
          validIO.getOrElseUpdate(key, mutable.Set.empty) ++= value
          }
          ins_io.foreach { case (key, value) =>
          module_sigs.getOrElseUpdate(key, mutable.Set.empty) ++= value
          if (!parsed_module.contains(key)) {
            not_parsed_module += key
          }
          }
          
        }

        val instance_name = type_instance_map.getOrElse(modulename, "")

        validIO.keys.foreach {io => if (tile_port_connect.contains(instance_name, io)) {
          connect_port((instance_name, io)) = tile_port_connect((instance_name, io))
          println(s"connect_port is: $connect_port")
          val Array(ins, out) = tile_port_connect((instance_name, io)).split("\\.")
          parsed_ins_type.getOrElseUpdate(ins, mutable.Set()) += instance_type_map(ins)
          module_sigs.getOrElseUpdate(instance_type_map(ins), mutable.Set()) += out
          if (!parsed_module.contains(instance_type_map(ins))) {
            not_parsed_module += instance_type_map(ins)
          }
        }
        }
        
        validIO.foreach { case (key, value) => 
          dependence_tree.getOrElseUpdate(modulename, mutable.Map.empty).getOrElseUpdate(key, mutable.Set.empty) ++= value
        }

        println(s"$modulename instance map is: ${graphLedger.getInstanceMap}")
        println(s"${modulename} is: ${validIO}")
        println(s"${modulename} rN2IP is: ${graphLedger.rN2IP}")
        // val valid_signals = dependence_signals.filter(_.contains("valid"))
        // val io_signals = dependence_signals.filter(_.contains("io"))

        // val tree_input = dependence_tree.keys

        // println(s"tile instance map is ${gLedgers.get("BoomTile").get.getInstanceMap}")
      
        // println(dependence_signals.mkString(", "))
        // println(s"Sensitve variables is: ${dependence_signals.size}")
        // println(s"Dependence_tree is: ${dependence_tree}")
        // println(s"valid variables is: ${valid_signals.mkString(", ")} size is: ${valid_signals.size}")
        // println(s"io variables is: ${io_signals.mkString(", ")} size is: ${io_signals.size}")
        // println(s"io valid variables is: ${valid_signals.filter(_.contains("io")).mkString(", ")} size is: ${valid_signals.filter(_.contains("io")).size}")
        println("")
        // println(s"getInstanceMap ${graphLedger.getInstanceMap}")
        println(s"mPorts: ${graphLedger.mPorts}")
        // graphLedger.mPorts.foreach {
        //   port => println(s"Port name is: ${port.name}, dir is: ${port.direction}, serialize is: ${port.serialize}")
        // }

        // println(s"findNodeSinks: ${graphLedger.findNodeSinks("hella_xcpt_pf_st")}")                                                                                                                                                               
        // println(s"findNodeSinks: ${graphLedger.findNodeSinks("io_dmem_resp_0_bits_uop_ldq_idx")}")
      
        // println(s"findNodeSrcs: ${graphLedger.findNodeSrcs(sensitive_signals.values.toSet)}")
        // println(s"N2E: ${graphLedger.N2E}")
      // 在这里使用dependence_signals
      } else {
        println(s"No graphLedger found for module $modulename")
        println(s"gLedgers: $gLedgers")
        println(s"sensitive_signals: $sensitive_signals")
      }

      println(s"Dependence_tree is: ${dependence_tree}")
      println(s"parsed_module is: ${parsed_module}")
      println(s"module_sigs is: ${module_sigs}")
      println(s"final connect_port is: $connect_port")
      println(s"parsed_ins_type is: $parsed_ins_type")

    }
    
    /* Log static analysis result */
    val basePath = "specdoctor-logs"
    val logPath = s"${basePath}/${circuit.main}"

    val dir = new File(basePath)
    val res_dir = new File(logPath)

    dir.mkdir()
    res_dir.mkdir()

    implicit val formats = Serialization.formats(NoTypeHints)

    var logger: PrintWriter = null

    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.mems.json"))
    logger.write(writePretty(atkMemMap.map{ case (m, set) => (m, set.map(_.name)) }))
    logger.close()

    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.regs.vector.json"))
    logger.write(writePretty(atkRegMap.map { case (m, map) =>
      (m, map.keySet.filter(!_.contains("remaining")
      ).map(r => r.substring(0, r.length - 1)))
    }.filter(_._2.nonEmpty)))
    logger.close()

    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.regs.scala.json"))
    logger.write(writePretty(atkRegMap.map{ case (m, map) =>
      (m, map.getOrElse("remaining_", Set[DefRegister]()).map(_.name))
    }.filter(_._2.nonEmpty)))
    logger.close()

    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.valids.json"))
    logger.write(writePretty(instMap.map{ case (_, s) =>
      (s._1, s._2.flatMap(_._2))}))
    logger.close()

    // Print the dependence tree
    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.deps.json"))
    logger.write(writePretty(dependence_tree.map{ case (m, s) =>
      (m, s.map { case (src, sink) => (src, sink)})
    }))
    logger.close()

    // Print the signal width
    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.width.json"))
    logger.write(writePretty(gLedgers.map { case (m, g) =>
      (m, g.NodeType.map { case (sig, w) => (sig, w) })
    }))
    logger.close()

    // Print the connect port relationship
    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.connect.json"))
    logger.write(writePretty(connect_port.map {case (in, out) => (in._1 + "," + in._2, out)}))
    logger.close()

    // Print the parsed instance type 
    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.instype.json"))
    logger.write(writePretty(parsed_ins_type.map {case (ins, types) => (ins, types)}))
    logger.close()

    // Print the ldq_idx in valid map
    val mod_ldq_map = gLedgers.values.filter(_.ldq_map.nonEmpty).map(gl => gl.mName -> gl.ldq_map).toMap
    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.ldq_map.json"))
    logger.write(writePretty(mod_ldq_map))
    logger.close()

    // Print the uop in valid map
    val mod_uops_map = gLedgers.values.filter(_.uops_map.nonEmpty).map(gl => gl.mName -> gl.uops_map).toMap
    logger = new PrintWriter(new File(s"${logPath}/${circuit.main}.uops_map.json"))
    logger.write(writePretty(mod_uops_map))
    logger.close()

    state.copy(circuit)
  }

  /* Phase 0
  Topdown backward slicing
  1. Sort modules following the module hierarchy
  2. Backward slicing starting from the topmost module's valid ports
  */
  def phase0(mNet: moduleNet, mInfos: Map[String, moduleInfo],
             topModuleName: String, topPorts: Set[String] = Set()): Unit = {

    mNet.reset

    val fPorts = mInfos.map{case (k, v) => (k, v.getPorts(mInfos))}
    fPorts(topModuleName).appendAll(topPorts)

    var done = false
    while (!done) {
      mNet.popT match {
        case Some(nName) =>
          mInfos(nName).phase0(fPorts(nName).toSet)
          mInfos(nName).getSrcNode[WDefInstance].foreach(
            inst => fPorts(inst.module).appendAll(mInfos(nName).fInstPorts(inst))
          )
        case None => done = true
      }
    }
  }

  /*  Phase 1
  Bottomup backward slicing
  After 'phase 0', input ports of each module (which are source of the valid signals) are identified.
  For them, backward slicing should be performed in reverse direction
  1. For each module, union the identified (wired into the valid signals) nodes
  2. Sort modules following the reversed module hierarchy
  3. Backward slicing from the bottommost module
  */
  def phase1(mNet: moduleNet, mInfos: Map[String, moduleInfo]): Unit = {

    mNet.reset

    val bCons = mInfos.map{case (k, v) => (k, v.getInstCons(mInfos))}

    var done = false
    while (!done) {
      mNet.popB match {
        case Some(nName) =>
          val exprs = mInfos(nName).getInstCons(mInfos)
          mInfos(nName).phase1(bCons(nName) ++ exprs)
        case None => done = true
      }
    }
  }

  def findValidIO(sensitive: String, parsed_io: mutable.Set[String], parsed: mutable.Set[String], not_parsed: mutable.Set[String], graph: graphLedger): (mutable.Map[String, mutable.Set[String]], mutable.Map[String, mutable.Set[String]]) = {
    var R = graph.R
    var rN2IP = graph.rN2IP
    var rIP2E = graph.rIP2E
    var inst_map = graph.getInstanceMap
    // var ldq_map : mutable.Map[String, mutable.Set[String]] = mutable.Map.empty.withDefaultValue(mutable.Set.empty)
    // port related with the internal instance module
    var ins_port : mutable.Map[String, mutable.Set[String]] = mutable.Map.empty.withDefaultValue(mutable.Set.empty)
    // var parsed: mutable.Set[String] = parsed
    // var not_parsed: mutable.Set[String] = not_parsed
    var valid_graph: mutable.Map[String, mutable.Set[String]] = mutable.Map.empty.withDefaultValue(mutable.Set.empty)
    while (!not_parsed.isEmpty) {
      val next: String = not_parsed.head
      not_parsed -= next
      if (R.contains(next) && !rN2IP.contains(next)) {
        println(s"sinks: ${next} -----> source: ${R(next)}")
        // println(s"parsed: ${parsed} -----> not_parsed: ${not_parsed}")

        // if (next.contains("uop_ldq_idx")) {
        //   val parts = next.split("uop_ldq_idx", 2)
        //   val parts1 = next.split("bits_uop_ldq_idx", 2)
        //   val prefix = parts(0)
        //   val prefix1 = parts1(0)
        //   println(s"prefix is: ${prefix}, ${prefix1}")
        //   if (R.contains(prefix + "valid"))
        //   ldq_map.getOrElseUpdate(next, mutable.Set.empty) += prefix + "valid"
        //   if (R.contains(prefix1 + "valid"))
        //   ldq_map.getOrElseUpdate(next, mutable.Set.empty) += prefix1 + "valid"
        // }
          
        if (next.contains("io") && next.contains("valid") && next != sensitive) {
        // if (next.contains("valid") && next != sensitive) {
          valid_graph.getOrElseUpdate(next, mutable.Set.empty) += sensitive
          println(s"valid graph is: ${valid_graph}")
          parsed_io += sensitive
          if (!parsed_io.contains(next)) {
            val (valids, ports) = findValidIO(next, parsed_io, mutable.Set.empty, mutable.Set(next), graph)
            valid_graph = valid_graph ++= valids.map {
              case (key, value) =>
              key -> (value ++ valid_graph.getOrElse(key, mutable.Set.empty))
            }
            ins_port = ins_port ++= ports.map {
              case (key, value) =>
              key -> (value ++ ins_port.getOrElse(key, mutable.Set.empty))
            }
          }
        }
        val values = R(next)
          values.foreach(value => {
          if (!parsed.contains(value)) {
            not_parsed += value
          }
        })
        // else {
        //   val values = R(next)
        //   values.foreach(value => {
        //   if (!parsed.contains(value)) {
        //     not_parsed += value
        //   }
        // })
      }
      // rN2IP means the internal variables or internal instance module and its (instance name, connect io(not only output, also the input))
      else if(rN2IP.contains(next)) {
        var values = rN2IP(next).map{ case (prefix, suffix) => s"$prefix.$suffix"}.toSet
        rN2IP(next) foreach { case (prefix, suffix) =>
          // TODO
          // Some valid signals maybe the input, so, need to check the port of this io, and then continue to backtrace the source io.
          if (suffix.contains("valid")) {
            ins_port.getOrElseUpdate(inst_map(prefix)._1, mutable.Set.empty) += suffix
            parsed_ins_type.getOrElseUpdate(prefix, mutable.Set()) += inst_map(prefix)._1
            valid_graph.getOrElseUpdate(s"$prefix.$suffix", mutable.Set.empty) += sensitive
            if (rIP2E.contains((prefix,suffix))) {
              input_io += s"$prefix.$suffix"
              println(s"input io is: $input_io")
              var expr = rIP2E((prefix,suffix))
              var src = mutable.Set() ++ findNames(expr)
              // println(s"expr serialize is: ${expr.serialize}")
              println(s"src is: $src")
              parsed_io += sensitive
              if (!parsed_io.contains(s"$prefix.$suffix")) {
                val (valids1, ports1) = findValidIO(s"$prefix.$suffix", parsed_io, mutable.Set.empty, src, graph)
                parsed ++= src
                valid_graph ++= valids1.map {
                  case (key, value) =>
                  key -> (value ++ valid_graph.getOrElse(key, mutable.Set.empty))
                }
                ins_port ++= ports1.map {
                  case (key, value) =>
                  key -> (value ++ ins_port.getOrElse(key, mutable.Set.empty))
                }
              }
            }
            println(s"module is: ${graph.mName}")
            println(s"ins_port is: $ins_port")
            println(s"valid_graph is: $valid_graph")
          }
        }
        println(s"Port sinks: ${next} -----> source: ${values}")
        parsed ++= values
      }
      parsed += next
    }
    // println(s"ldq idx map is: ${ldq_map}")
    (valid_graph, ins_port)
  }

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

