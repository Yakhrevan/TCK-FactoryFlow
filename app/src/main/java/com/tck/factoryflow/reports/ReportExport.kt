package com.tck.factoryflow.reports

import com.tck.factoryflow.data.*
import java.time.LocalDate

data class ReportTotals(val produced:Int,val good:Int,val rejected:Int,val packedBottles:Int,val completePacks:Int,val dispatchedPacks:Int,val electricity:Double)
object ReportExport {
 fun inRange(date:String,from:LocalDate,to:LocalDate):Boolean {
  require(!to.isBefore(from)){"End date must be on or after start date."}
  val parsed=runCatching{LocalDate.parse(date)}.getOrNull()?:return false
  return !parsed.isBefore(from)&&!parsed.isAfter(to)
 }
 fun totals(from:LocalDate,to:LocalDate,production:List<ProductionEntryEntity>,packing:List<PackingEntryEntity>,dispatches:List<DispatchEntryEntity>,meters:List<MeterReadingEntity>,dispatchBatches:List<DispatchBatchEntity> = emptyList(),dispatchLines:List<DispatchLineEntity> = emptyList()):ReportTotals {
  require(!to.isBefore(from)){"End date must be on or after start date."}
  val p=production.filter{inRange(it.productionDate,from,to)}
  val pk=packing.filter{inRange(it.packingDate,from,to)}
  val d=dispatches.filter{inRange(it.dispatchDate,from,to)}
  val m=meters.filter{inRange(it.readingDate,from,to)}
  val postedIds=dispatchBatches.filter{it.status=="POSTED"&&inRange(it.dispatchDate,from,to)}.map{it.id}.toSet()
  return ReportTotals(p.sumOf{it.producedBottles},p.sumOf{it.goodBottles},p.sumOf{it.rejectedBottles},pk.sumOf{it.bottlesConsumed},pk.sumOf{it.completePacks},d.sumOf{it.packsDispatched}+dispatchLines.filter{it.batchId in postedIds}.sumOf{it.packs},m.sumOf{it.consumption})
 }
 fun csv(production:List<ProductionEntryEntity>,packing:List<PackingEntryEntity>,dispatches:List<DispatchEntryEntity>,meters:List<MeterReadingEntity>,dispatchBatches:List<DispatchBatchEntity> = emptyList(),dispatchLines:List<DispatchLineEntity> = emptyList()):String {
  val out=StringBuilder("section,date,reference_or_product,quantity,unit,details\r\n")
  production.forEach{out.append(listOf("production",it.productionDate,it.productNameSnapshot,it.producedBottles.toString(),"bottles","good="+it.goodBottles+"; rejected="+it.rejectedBottles+"; machine="+it.machineName+"; line="+(it.lineNameSnapshot?: "Unassigned")).joinToString(","){v->escape(v)}).append("\r\n")}
  packing.forEach{out.append(listOf("packing",it.packingDate,it.productNameSnapshot,it.completePacks.toString(),"packs","bottles="+it.bottlesConsumed+"; loose="+it.looseBottles).joinToString(","){v->escape(v)}).append("\r\n")}
  dispatches.forEach{out.append(listOf("dispatch",it.dispatchDate,it.productNameSnapshot,it.packsDispatched.toString(),"packs","customer="+it.customerName+"; reference="+it.dispatchReference).joinToString(","){v->escape(v)}).append("\r\n")}
  val batchesById=dispatchBatches.associateBy{it.id}
  dispatchLines.forEach{line->val batch=batchesById[line.batchId];if(batch!=null)out.append(listOf("dispatch",batch.dispatchDate,line.productNameSnapshot,line.packs.toString(),"packs","customer="+batch.customerNameSnapshot+"; reference="+batch.dispatchReference+"; status="+batch.status).joinToString(","){v->escape(v)}).append("\r\n")}
  meters.forEach{out.append(listOf("electricity",it.readingDate,it.meterId,it.consumption.toString(),"kWh","start="+it.startReading+"; end="+it.endReading).joinToString(","){v->escape(v)}).append("\r\n")}
  return out.toString()
 }
 private fun escape(value:String):String="\""+value.replace("\"","\"\"")+"\""
}
