package boom.ifu

import chisel3._
import chisel3.util._
import chisel3.experimental.dontTouch

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.common._
import boom.util.{BoomCoreStringPrefix}

import scala.math.min

case class BoomLoopPredictorParams(
  nWays: Int = 4,
  threshold: Int = 7
)

class LoopBranchPredictorBank(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  val tagSz = 10
  override val nSets = 32



  class LoopMeta extends Bundle {
    val s_cnt   = UInt(10.W)
  }

  class LoopEntry extends Bundle {
    val tag   = UInt(tagSz.W)
    val conf  = UInt(3.W)
    val p_cnt = UInt(10.W)
    val s_cnt = UInt(10.W)
  }

  class LoopBranchPredictorColumn extends Module {



    val io = IO(new Bundle {
      val f2_req_valid = Input(Bool())
      val f2_req_idx  = Input(UInt())
      val f3_req_fire = Input(Bool())

      val f3_pred_in  = Input(Bool())
      val f3_pred     = Output(Bool())
      val f3_meta     = Output(new LoopMeta)

      val mispredict_valid = Input(Bool())
      val mispredict_idx   = Input(UInt())
      val mispredict_resolve_dir = Input(Bool())
      val mispredict_meta  = Input(new LoopMeta)
    })

    val doing_reset = RegInit(true.B)
    val reset_idx = RegInit(0.U(log2Ceil(nSets).W))
    reset_idx := reset_idx + doing_reset
    when (reset_idx === (nSets-1).U) { doing_reset := false.B }


    val entries = Reg(Vec(nSets, new LoopEntry))
    val f3_entry = RegNext(entries(io.f2_req_idx))
    val f3_tag   = RegNext(io.f2_req_idx(tagSz+log2Ceil(nSets)-1,log2Ceil(nSets)))

    io.f3_pred := io.f3_pred_in
    io.f3_meta.s_cnt := f3_entry.s_cnt

    when (io.f3_req_fire) {
      when (f3_entry.tag === f3_tag) {
        when (f3_entry.s_cnt === f3_entry.p_cnt && f3_entry.conf === 7.U) {
          io.f3_pred := !io.f3_pred_in
          entries(RegNext(io.f2_req_idx)).s_cnt := 0.U
        } .otherwise {
          entries(RegNext(io.f2_req_idx)).s_cnt := f3_entry.s_cnt + 1.U
        }
      }
    }

    when (io.mispredict_valid && !doing_reset) {
      val entry = entries(io.mispredict_idx)
      val tag = io.mispredict_idx(tagSz+log2Ceil(nSets)-1,log2Ceil(nSets))
      val tag_match = entry.tag === tag
      val ctr_match = entry.p_cnt === io.mispredict_meta.s_cnt
      val wentry = WireInit(entry)

      // Learned, tag match -> decrement confidence
      when (entry.conf === 7.U && tag_match) {
        wentry.s_cnt := 0.U
        wentry.conf  := 0.U

      // Learned, no tag match -> (TODO: decrement age counters)
      } .elsewhen (entry.conf === 7.U && !tag_match) {

      // Confident, tag match, ctr_match -> increment confidence, reset counter
      } .elsewhen (entry.conf =/= 0.U && tag_match && ctr_match) {
        wentry.conf  := entry.conf + 1.U
        wentry.s_cnt := 0.U

      // Confident, tag match, no ctr match -> zero confidence, reset counter, set previous counter
      } .elsewhen (entry.conf =/= 0.U && tag_match && !ctr_match) {
        wentry.conf  := 0.U
        wentry.s_cnt := 0.U
        wentry.p_cnt := io.mispredict_meta.s_cnt

      // Confident, no tag match -> (TODO: decrement age counters)
      } .elsewhen (entry.conf =/= 0.U && !tag_match) {

      // Unconfident, tag match, ctr match -> increment confidence
      } .elsewhen (entry.conf === 0.U && tag_match && ctr_match) {
        wentry.conf  := 1.U
        wentry.s_cnt := 0.U

      // Unconfident, tag match, no ctr match -> set previous counter
      } .elsewhen (entry.conf === 0.U && tag_match && !ctr_match) {
        wentry.p_cnt := io.mispredict_meta.s_cnt
        wentry.s_cnt := 0.U

      // Unconfident, no tag match -> set previous counter and tag
      } .elsewhen (entry.conf === 0.U && !tag_match) {
        wentry.tag := tag
        wentry.conf := 1.U
        wentry.s_cnt := 0.U
        wentry.p_cnt := io.mispredict_meta.s_cnt
      }

      entries(io.mispredict_idx) := wentry


      // when (entry.conf === 0.U || entry.p_cnt =/= io.mispredict_meta.s_cnt) {
      //   entries(io.mispredict_idx).p_cnt := io.mispredict_meta.s_cnt
      // }
      // when (entry.conf =/= 7.U && entry.p_cnt === io.mispredict_meta.s_cnt) {
      //   entries(io.mispredict_idx).conf := entry.conf + 1.U
      // }
      // when (entry.conf === 7.U ||
      //       entry.p_cnt =/= io.mispredict_meta.s_cnt) {
      //   entries(io.mispredict_idx).conf := 0.U
      // }
      // entries(io.mispredict_idx).s_cnt := 0.U
    }

    when (doing_reset) {
      entries(reset_idx) := (0.U).asTypeOf(new LoopEntry)
    }

    dontTouch(entries)
    // when (reset.asBool) {
    //   entries.map { e => e.age := 0.U }
    // }
  }


  val columns = Seq.fill(bankWidth) { Module(new LoopBranchPredictorColumn) }

  val f3_meta = Wire(Vec(bankWidth, new LoopMeta))
  override val metaSz = f3_meta.asUInt.getWidth

  val update_meta = s1_update.bits.meta.asTypeOf(Vec(bankWidth, new LoopMeta))

  for (w <- 0 until bankWidth) {
    columns(w).io.f2_req_valid := s2_req.valid
    columns(w).io.f2_req_idx  := s2_req_idx
    columns(w).io.f3_req_fire := s3_req.valid && io.f3_fire && RegNext(io.resp_in.f2(w).predicted_pc.valid && io.resp_in.f2(w).is_br)

    columns(w).io.f3_pred_in  := io.resp_in.f3(w).taken
    io.resp.f3(w).taken       := columns(w).io.f3_pred

    columns(w).io.mispredict_valid       := s1_update.valid && s1_update.bits.br_mask(w) && s1_update.bits.is_spec && s1_update.bits.cfi_mispredicted
    columns(w).io.mispredict_idx         := s1_update_idx
    columns(w).io.mispredict_resolve_dir := s1_update.bits.cfi_taken
    columns(w).io.mispredict_meta        := update_meta(w)

    f3_meta(w) := columns(w).io.f3_meta
  }

  io.f3_meta := f3_meta.asUInt

}
