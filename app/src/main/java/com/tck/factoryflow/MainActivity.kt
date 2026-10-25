package com.tck.factoryflow
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tck.factoryflow.data.*
import com.tck.factoryflow.domain.*
import com.tck.factoryflow.reports.ReportExport
import com.tck.factoryflow.auth.FactoryAuthRepository
import com.tck.factoryflow.auth.FactorySession
import com.tck.factoryflow.auth.FactoryPermissions
import com.tck.factoryflow.auth.FactoryRole
import com.tck.factoryflow.auth.FactoryAction
import com.tck.factoryflow.sync.SyncScheduler
import com.tck.factoryflow.ui.components.BottleProductCard
import com.tck.factoryflow.ui.components.DashboardMetricCard
import com.tck.factoryflow.ui.components.PackCalculationCard
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private val Blue=Color(0xFF0756A4); private val Cyan=Color(0xFF13A7D8); private val Navy=Color(0xFF102B4E); private val Page=Color(0xFFF1F7FC); private val Muted=Color(0xFF6B7E93); private val Border=Color(0xFFDCE7F1)
private fun today()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
private fun roleAllows(session:FactorySession?,action:FactoryAction):Boolean = if(session==null) true else runCatching{FactoryPermissions.requireAllowed(FactoryPermissions.fromClaim(session.role),action)}.isSuccess
class MainActivity:ComponentActivity(){
 private val db by lazy{FactoryDatabase.get(this)}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);lifecycleScope.launch{if(db.factoryDao().activeProducts().isEmpty()) listOf(ProductEntity(name="300ml",sizeLabel="300ml",bottlesPerPack=0),ProductEntity(name="500ml",sizeLabel="500ml",bottlesPerPack=0),ProductEntity(name="1 Ltr",sizeLabel="1 litre",bottlesPerPack=0),ProductEntity(name="200ml Green",sizeLabel="200ml",colour="Green",bottlesPerPack=0),ProductEntity(name="200ml Milk White",sizeLabel="200ml",colour="Milk White",bottlesPerPack=0),ProductEntity(name="200ml Clear",sizeLabel="200ml",bottlesPerPack=0)).forEach{runCatching{db.factoryDao().insertProduct(it)}}};if(FirebaseApp.getApps(this).isNotEmpty()){SyncScheduler.schedule(this);SyncScheduler.runNow(this)};setContent{FactoryApp(db.factoryDao())}}
}

@Composable private fun HomeKpiCard(title:String,value:String,detail:String,icon:androidx.compose.ui.graphics.vector.ImageVector,accent:Color,modifier:Modifier=Modifier){
 Surface(modifier=modifier,shape=RoundedCornerShape(18.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,Border),shadowElevation=1.dp){
  Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
    Surface(color=accent.copy(alpha=0.12f),shape=RoundedCornerShape(10.dp)){Icon(icon,null,tint=accent,modifier=Modifier.padding(7.dp).size(19.dp))}
    Text(title,fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=Muted,maxLines=2)
   }
   Text(value,fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=Navy,maxLines=1)
   Text(detail,fontSize=10.sp,color=Muted,maxLines=1)
  }
 }
}
@Composable private fun HomeActionTile(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,accent:Color,onClick:()->Unit,modifier:Modifier=Modifier){
 Surface(modifier=modifier,shape=RoundedCornerShape(14.dp),color=accent.copy(alpha=0.09f),border=androidx.compose.foundation.BorderStroke(1.dp,accent.copy(alpha=0.18f)),onClick=onClick){
  Column(Modifier.fillMaxWidth().padding(vertical=12.dp,horizontal=3.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)){
   Icon(icon,null,tint=accent,modifier=Modifier.size(22.dp))
   Text(label,fontSize=10.sp,fontWeight=FontWeight.Bold,color=Navy,maxLines=1)
  }
 }
}

private enum class PageTab{HOME,PRODUCTS,PRODUCTION,PACKING,STOCK,DISPATCH,ELECTRICITY,REPORTS,CUSTOMERS,MACHINES,QUALITY,PLANNING,ORDERS,MOVEMENTS,AUDIT,STAFF}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FactoryApp(dao:FactoryDao){
 var tab by remember{mutableStateOf(PageTab.HOME)}; var showTools by remember{mutableStateOf(false)}; var dialog by remember{mutableStateOf("")}; var session by remember{mutableStateOf<FactorySession?>(null)}; var editingProduct by remember{mutableStateOf<ProductEntity?>(null)}; var editingCustomer by remember{mutableStateOf<CustomerEntity?>(null)}; var selectedMachineId by remember{mutableStateOf<Long?>(null)}; var selectedOrderId by remember{mutableStateOf<Long?>(null)}; var reversalBatchId by remember{mutableStateOf<Long?>(null)}; var reversalPackingId by remember{mutableStateOf<Long?>(null)}; var message by remember{mutableStateOf<String?>(null)}; var reportFrom by remember{mutableStateOf(today().substring(0,7)+"-01")}; var reportTo by remember{mutableStateOf(today())}; var productSearch by remember{mutableStateOf("")}; var movementSearch by remember{mutableStateOf("")}; var stockSearch by remember{mutableStateOf("")}; var productionSearch by remember{mutableStateOf("")}; var customerSearch by remember{mutableStateOf("")}; var dispatchSearch by remember{mutableStateOf("")}; val scope=rememberCoroutineScope()
 val products by dao.observeProducts().collectAsStateWithLifecycle(emptyList()); val production by dao.observeProduction().collectAsStateWithLifecycle(emptyList()); val packing by dao.observePacking().collectAsStateWithLifecycle(emptyList()); val dispatches by dao.observeDispatches().collectAsStateWithLifecycle(emptyList()); val dispatchBatches by dao.observeDispatchBatches().collectAsStateWithLifecycle(emptyList()); val dispatchLines by dao.observeDispatchLines().collectAsStateWithLifecycle(emptyList()); val meters by dao.observeMeterReadings().collectAsStateWithLifecycle(emptyList()); val customers by dao.observeCustomers().collectAsStateWithLifecycle(emptyList()); val machines by dao.observeMachines().collectAsStateWithLifecycle(emptyList()); val machineEvents by dao.observeMachineEvents().collectAsStateWithLifecycle(emptyList()); val qualityInspections by dao.observeQualityInspections().collectAsStateWithLifecycle(emptyList()); val plans by dao.observeProductionPlans().collectAsStateWithLifecycle(emptyList()); val orders by dao.observeSalesOrders().collectAsStateWithLifecycle(emptyList()); val openOrders by dao.observeOpenSalesOrders().collectAsStateWithLifecycle(emptyList()); val orderLines by dao.observeSalesOrderLines().collectAsStateWithLifecycle(emptyList()); val inventoryMovements by dao.observeRecentInventoryMovements().collectAsStateWithLifecycle(emptyList()); val auditEvents by dao.observeAuditEvents().collectAsStateWithLifecycle(emptyList()); val active=products.filter{it.active}; val date=today()
 val context=LocalContext.current
 LaunchedEffect(context){session=runCatching{FactoryAuthRepository(context).currentSession()}.getOrNull()}
 val backupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
  if(uri!=null) runCatching {
   val root=JSONObject().put("format","tck-factoryflow-backup").put("version",1).put("exportedAt",System.currentTimeMillis())
   root.put("products",JSONArray().apply{products.forEach{put(JSONObject().put("id",it.id).put("name",it.name).put("sizeLabel",it.sizeLabel).put("colour",it.colour).put("bottlesPerPack",it.bottlesPerPack).put("active",it.active).put("createdAt",it.createdAt))}})
   root.put("production",JSONArray().apply{production.forEach{put(JSONObject().put("id",it.id).put("productId",it.productId).put("productName",it.productNameSnapshot).put("date",it.productionDate).put("produced",it.producedBottles).put("good",it.goodBottles).put("rejected",it.rejectedBottles).put("machine",it.machineName).put("remarks",it.remarks).put("createdAt",it.createdAt))}})
   root.put("packing",JSONArray().apply{packing.forEach{put(JSONObject().put("id",it.id).put("productId",it.productId).put("productName",it.productNameSnapshot).put("date",it.packingDate).put("bottlesConsumed",it.bottlesConsumed).put("completePacks",it.completePacks).put("looseBottles",it.looseBottles).put("createdAt",it.createdAt))}})
   root.put("dispatchBatches",JSONArray().apply{dispatchBatches.forEach{put(JSONObject().put("id",it.id).put("reference",it.dispatchReference).put("date",it.dispatchDate).put("customer",it.customerNameSnapshot).put("status",it.status).put("remarks",it.remarks).put("createdAt",it.createdAt))}})
   root.put("dispatchLines",JSONArray().apply{dispatchLines.forEach{put(JSONObject().put("id",it.id).put("batchId",it.batchId).put("productId",it.productId).put("productName",it.productNameSnapshot).put("packs",it.packs).put("bottles",it.bottles))}})
   root.put("customers",JSONArray().apply{customers.forEach{put(JSONObject().put("id",it.id).put("name",it.name).put("phone",it.phone).put("address",it.address).put("gstNumber",it.gstNumber).put("active",it.active))}})
   root.put("meterReadings",JSONArray().apply{meters.forEach{put(JSONObject().put("id",it.id).put("meterId",it.meterId).put("date",it.readingDate).put("start",it.startReading).put("end",it.endReading).put("consumption",it.consumption).put("remarks",it.remarks))}})
   root.put("machines",JSONArray().apply{machines.forEach{put(JSONObject().put("id",it.id).put("code",it.machineCode).put("name",it.name).put("type",it.machineType).put("location",it.location).put("status",it.status).put("notes",it.notes))}})
   root.put("machineEvents",JSONArray().apply{machineEvents.forEach{put(JSONObject().put("id",it.id).put("machineId",it.machineId).put("machineName",it.machineNameSnapshot).put("type",it.eventType).put("startedAt",it.startedAt).put("endedAt",it.endedAt).put("reason",it.reason).put("maintenanceType",it.maintenanceType).put("status",it.status).put("performedBy",it.performedBy))}})
   root.put("qualityInspections",JSONArray().apply{qualityInspections.forEach{put(JSONObject().put("id",it.id).put("productId",it.productId).put("productName",it.productNameSnapshot).put("date",it.inspectionDate).put("inspected",it.inspectedBottles).put("passed",it.passedBottles).put("failed",it.failedBottles).put("defect",it.defectCategory).put("severity",it.severity).put("notes",it.notes).put("inspector",it.inspectorId))}})
   root.put("productionPlans",JSONArray().apply{plans.forEach{put(JSONObject().put("id",it.id).put("date",it.planDate).put("shift",it.shift).put("productId",it.productId).put("productName",it.productNameSnapshot).put("target",it.targetBottles).put("machineId",it.assignedMachineId).put("status",it.status).put("notes",it.notes))}})
   root.put("salesOrders",JSONArray().apply{orders.forEach{put(JSONObject().put("id",it.id).put("orderNumber",it.orderNumber).put("customerId",it.customerId).put("customer",it.customerNameSnapshot).put("orderDate",it.orderDate).put("dueDate",it.dueDate).put("status",it.status).put("notes",it.notes))}})
   root.put("salesOrderLines",JSONArray().apply{orderLines.forEach{put(JSONObject().put("id",it.id).put("orderId",it.orderId).put("productId",it.productId).put("productName",it.productNameSnapshot).put("packsOrdered",it.packsOrdered).put("packsFulfilled",it.packsDispatched).put("packSize",it.packSizeSnapshot))}})
   root.put("inventoryMovements",JSONArray().apply{inventoryMovements.forEach{put(JSONObject().put("id",it.id).put("productId",it.productId).put("productName",it.productNameSnapshot).put("type",it.movementType).put("quantity",it.quantity).put("unit",it.unit).put("sourceType",it.sourceType).put("sourceId",it.sourceId).put("note",it.note).put("actorId",it.actorId).put("createdAt",it.createdAt))}})
   root.put("auditEvents",JSONArray().apply{auditEvents.forEach{put(JSONObject().put("id",it.id).put("eventType",it.eventType).put("entityType",it.entityType).put("entityId",it.entityId).put("before",it.beforeJson).put("after",it.afterJson).put("reason",it.reason).put("actorId",it.actorId).put("createdAt",it.createdAt))}})
   context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(root.toString(2))}?:error("Could not open backup destination")
  }.onSuccess{message="JSON backup exported"}.onFailure{message="Backup export failed: "+it.message}
 }
 val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
  if(uri!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ReportExport.csv(production,packing,dispatches,meters,dispatchBatches,dispatchLines)) } ?: error("Could not open export destination") }.onSuccess { message="CSV report exported" }.onFailure { message="Export failed: "+it.message }
 }
 MaterialTheme(colorScheme=lightColorScheme(primary=Blue,secondary=Cyan,background=Page,surface=Color.White,outline=Border)){
 Scaffold(topBar={TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=RoundedCornerShape(12.dp),color=Navy,modifier=Modifier.size(42.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Inventory2,null,tint=Color.White,modifier=Modifier.size(25.dp))}};Column{Text("TCK FactoryFlow",fontWeight=FontWeight.ExtraBold,color=Navy,fontSize=18.sp);Text("MANUFACTURING OPERATIONS",fontSize=9.sp,color=Muted,letterSpacing=1.2.sp)} }},actions={Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={dialog="login"}){Column(horizontalAlignment=Alignment.End){Text(session?.let{it.role.uppercase()}?:"SIGN IN",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue);Text(if(session!=null)"ACCOUNT" else "LOCAL MODE",fontSize=8.sp,color=Muted)}};Box{IconButton(onClick={showTools=true}){Icon(Icons.Default.MenuOpen,null,tint=Navy)};DropdownMenu(expanded=showTools,onDismissRequest={showTools=false}){DropdownMenuItem(text={Text("Export full JSON backup")},leadingIcon={Icon(Icons.Default.Backup,null)},onClick={showTools=false;backupLauncher.launch("tck-factoryflow-backup-"+date+".json")};listOf(PageTab.DISPATCH,PageTab.ELECTRICITY,PageTab.CUSTOMERS,PageTab.MACHINES,PageTab.QUALITY,PageTab.PLANNING,PageTab.ORDERS,PageTab.MOVEMENTS,PageTab.AUDIT,PageTab.STAFF).forEach{t->DropdownMenuItem(text={Text(when(t){PageTab.DISPATCH->"Dispatch";PageTab.ELECTRICITY->"Electricity";PageTab.CUSTOMERS->"Customers";PageTab.MACHINES->"Machine management";PageTab.QUALITY->"Quality inspections";PageTab.PLANNING->"Production planning";PageTab.ORDERS->"Sales orders";PageTab.MOVEMENTS->"Stock movement history";PageTab.STAFF->"Staff access & permissions";else->"Audit history"})},onClick={tab=t;showTools=false})}}}}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.White))},bottomBar={NavigationBar(containerColor=Color.White,tonalElevation=8.dp){listOf(PageTab.HOME,PageTab.PRODUCTION,PageTab.STOCK,PageTab.REPORTS,PageTab.PRODUCTS).forEach{t->NavigationBarItem(selected=tab==t,onClick={tab=t},icon={Icon(when(t){PageTab.HOME->Icons.Default.Home;PageTab.PRODUCTION->Icons.Default.Build;PageTab.STOCK->Icons.Default.Inventory2;PageTab.REPORTS->Icons.Default.BarChart;else->Icons.Default.WaterDrop},null)},label={Text(t.name.lowercase().replaceFirstChar{it.uppercase()},fontSize=10.sp,fontWeight=if(tab==t)FontWeight.Bold else FontWeight.Normal)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Navy,selectedTextColor=Navy,indicatorColor=Color(0xFFE7DDF6),unselectedIconColor=Muted,unselectedTextColor=Muted))}}},containerColor=Page){padding->
 Box(Modifier.fillMaxSize().padding(padding)){
 when(tab){
 PageTab.HOME->{
  val todayProduction=production.filter{it.productionDate==date}
  val todayPacking=packing.filter{it.packingDate==date}
  val todayMeters=meters.filter{it.readingDate==date}
  val produced=todayProduction.sumOf{it.producedBottles}
  val good=todayProduction.sumOf{it.goodBottles}
  val rejected=todayProduction.sumOf{it.rejectedBottles}
  val rejectionRate=if(produced>0) rejected*100.0/produced else 0.0
  val packed=todayPacking.sumOf{it.completePacks}
  val dispatched=dispatches.filter{it.dispatchDate==date}.sumOf{it.packsDispatched}+dispatchLines.filter{line->line.batchId in dispatchBatches.filter{it.status=="POSTED"&&it.dispatchDate==date}.map{it.id}}.sumOf{it.packs}
  val packedAll=packing.groupBy{it.productId}.mapValues{(_,entries)->entries.sumOf{it.completePacks}}
  val legacyOut=dispatches.groupBy{it.productId}.mapValues{(_,entries)->entries.sumOf{it.packsDispatched}}
  val postedBatchIds=dispatchBatches.filter{it.status=="POSTED"}.map{it.id}.toSet()
  val batchOut=dispatchLines.filter{it.batchId in postedBatchIds}.groupBy{it.productId}.mapValues{(_,entries)->entries.sumOf{it.packs}}
  val lowStock=active.filter{p->((packedAll[p.id]?:0)-(legacyOut[p.id]?:0)-(batchOut[p.id]?:0)).coerceAtLeast(0)<=5}
  val availablePacks=active.sumOf{p->((packedAll[p.id]?:0)-(legacyOut[p.id]?:0)-(batchOut[p.id]?:0)).coerceAtLeast(0)}
  LazyColumn(Modifier.fillMaxSize().background(Color(0xFFF3F7FC)),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=14.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   item{
    Surface(color=Color(0xFF0B1B31),shape=RoundedCornerShape(24.dp)){
     Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("TCK FACTORYFLOW",fontSize=10.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFF62C9FF),letterSpacing=1.8.sp)
        Text("Factory overview",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
        Text(java.text.SimpleDateFormat("EEE, dd MMM yyyy",Locale.US).format(Date())+" · Live factory records",fontSize=12.sp,color=Color(0xFFB9CBE0))
       }
       Surface(color=Color(0xFF1B3858),shape=RoundedCornerShape(14.dp)){
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
         Icon(Icons.Default.WbSunny,null,tint=Color(0xFFFFC857),modifier=Modifier.size(20.dp))
         Text("DAY SHIFT",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color.White)
        }
       }
      }
      HorizontalDivider(color=Color(0xFF29435F))
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
       Icon(Icons.Default.Circle,null,tint=if(session!=null)Color(0xFF36D399) else Color(0xFFFFC857),modifier=Modifier.size(9.dp))
       Text(if(session!=null)"Connected · "+session!!.role else "Local device mode · Sign in for staff access",fontSize=11.sp,color=Color(0xFFD9E7F5))
       Spacer(Modifier.weight(1f))
       TextButton(onClick={dialog="login"},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text(if(session!=null)"Account" else "Sign in",color=Color(0xFF75D2FF),fontSize=11.sp)}
      }
     }
    }
   }
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("Today's performance",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Actual saved totals · no sample values",fontSize=11.sp,color=Muted)};TextButton(onClick={tab=PageTab.REPORTS}){Text("Reports  →")}}}
   item{
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
     HomeKpiCard("Produced today",produced.toString(),"Bottles",Icons.Default.WaterDrop,Color(0xFF147DEB),Modifier.weight(1f))
     HomeKpiCard("Good bottles",good.toString(),"Accepted output",Icons.Default.CheckCircle,Color(0xFF119B79),Modifier.weight(1f))
    }
   }
   item{
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
     HomeKpiCard("Rejected",rejected.toString(),String.format(Locale.US,"%.1f%% reject rate",rejectionRate),Icons.Default.WarningAmber,Color(0xFFD94F70),Modifier.weight(1f))
     HomeKpiCard("Packs completed",packed.toString(),"Today",Icons.Default.Inventory2,Color(0xFF7656D9),Modifier.weight(1f))
    }
   }
   item{
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
     HomeKpiCard("Packs dispatched",dispatched.toString(),"Today's dispatches",Icons.Default.LocalShipping,Color(0xFFD98523),Modifier.weight(1f))
     HomeKpiCard("Electricity",String.format(Locale.US,"%.1f",todayMeters.sumOf{it.consumption}),"Units today",Icons.Default.Bolt,Color(0xFF1689A8),Modifier.weight(1f))
    }
   }
   item{
    Surface(color=Color.White,shape=RoundedCornerShape(20.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("Quick operations",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Start a common factory task",fontSize=11.sp,color=Muted)};Icon(Icons.Default.Bolt,null,tint=Cyan)}
      Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){HomeActionTile("Production",Icons.Default.Build,Color(0xFF147DEB),{dialog="production"},Modifier.weight(1f));HomeActionTile("Packing",Icons.Default.Inventory2,Color(0xFF7656D9),{dialog="packing"},Modifier.weight(1f));HomeActionTile("Dispatch",Icons.Default.LocalShipping,Color(0xFFD98523),{dialog="dispatch"},Modifier.weight(1f))}
      Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){HomeActionTile("Products",Icons.Default.WaterDrop,Color(0xFF168B78),{tab=PageTab.PRODUCTS},Modifier.weight(1f));HomeActionTile("Electricity",Icons.Default.Bolt,Color(0xFF1689A8),{dialog="meter"},Modifier.weight(1f));HomeActionTile("Customers",Icons.Default.People,Color(0xFF5369DA),{tab=PageTab.CUSTOMERS},Modifier.weight(1f))}
     }
    }
   }
   item{
    Surface(color=if(lowStock.isEmpty())Color(0xFFE8F7F0) else Color(0xFFFFF3DE),shape=RoundedCornerShape(20.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(lowStock.isEmpty())Color(0xFFC5E9D8) else Color(0xFFF2D6A5))){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
       Surface(color=if(lowStock.isEmpty())Color(0xFFD1F1E1) else Color(0xFFFFE6B6),shape=RoundedCornerShape(12.dp)){Icon(if(lowStock.isEmpty())Icons.Default.CheckCircle else Icons.Default.WarningAmber,null,tint=if(lowStock.isEmpty())Color(0xFF16804A) else Color(0xFF9B5B00),modifier=Modifier.padding(10.dp))}
       Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(if(lowStock.isEmpty())"Stock overview" else "Stock needs attention",fontWeight=FontWeight.Bold,color=Navy,fontSize=15.sp);Text(if(lowStock.isEmpty())active.size.toString()+" active products · "+availablePacks+" available packs" else lowStock.size.toString()+" product(s) at or below the 5-pack alert threshold",fontSize=11.sp,color=Muted)}
       TextButton(onClick={tab=PageTab.STOCK}){Text("View stock")}
      }
      if(lowStock.isNotEmpty())lowStock.take(3).forEach{p->Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.WaterDrop,null,tint=Color(0xFFB7791F),modifier=Modifier.size(17.dp));Text(p.name,Modifier.weight(1f),fontSize=12.sp,color=Navy);Text(((packedAll[p.id]?:0)-(legacyOut[p.id]?:0)-(batchOut[p.id]?:0)).coerceAtLeast(0).toString()+" packs",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Navy)}}
     }
    }
   }
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Product configuration",fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Check bottle and pack setup",fontSize=11.sp,color=Muted)};TextButton(onClick={tab=PageTab.PRODUCTS}){Text("Manage →")}}}
   if(active.isEmpty())item{EmptyDashboardState("No active products","Add your factory's bottle types to start recording operations.",Icons.Default.WaterDrop)}
   items(active.take(6),key={it.id}){p->BottleProductCard(name=p.name,capacity=p.sizeLabel,packSizeLabel=if(p.bottlesPerPack>0)p.bottlesPerPack.toString()+" bottles per pack" else "Pack size not configured",active=p.active,bottleType=p.colour,onClick={editingProduct=p;dialog="product"})}
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Recent production",fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Latest saved production entries",fontSize=11.sp,color=Muted)};TextButton(onClick={tab=PageTab.PRODUCTION}){Text("View all →")}}}
   if(production.isEmpty())item{EmptyDashboardState("No production entries yet","Record the first production run to populate your dashboard.",Icons.Default.Build)}
   else items(production.take(5),key={it.id}){e->Record(e.productNameSnapshot,e.goodBottles.toString()+" good · "+e.rejectedBottles+" rejected",e.productionDate)}
  }
 }
 PageTab.PRODUCTS->{
  val shown=products.filter{(it.name+" "+it.sizeLabel+" "+it.colour).contains(productSearch,true)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("PRODUCT CATALOG",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Products",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(products.count{it.active}.toString()+" active products · "+products.count{!it.active}+" archived",fontSize=12.sp,color=Muted)};Button(onClick={editingProduct=null;dialog="product"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(5.dp));Text("Add product")}}}
   item{OutlinedTextField(productSearch,{productSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search name, size or colour")},label={Text("Find a product")})}
   item{Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(16.dp)){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.Info,null,tint=Blue);Column{Text("Pack-size configuration",fontWeight=FontWeight.Bold,color=Navy);Text("Set bottles per pack in each product before packing or dispatch. Existing product records are preserved.",fontSize=11.sp,color=Muted)}}}}
   if(shown.isEmpty())item{EmptyDashboardState("No matching products","Try another search or add a product.",Icons.Default.Search)}
   items(shown,key={it.id}){p->
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
       Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.WaterDrop,null,tint=Cyan,modifier=Modifier.padding(12.dp).size(28.dp))}
       Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=16.sp);Text(p.sizeLabel+" · "+p.colour,fontSize=12.sp,color=Muted)}
       Surface(color=if(p.active)Color(0xFFE8F7F0) else Color(0xFFE9EDF2),shape=RoundedCornerShape(30.dp)){Text(if(p.active)"ACTIVE" else "ARCHIVED",modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(p.active)Color(0xFF16804A) else Muted)}
      }
      HorizontalDivider(color=Border)
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Pack configuration",fontSize=10.sp,color=Muted);Text(if(p.bottlesPerPack>0)p.bottlesPerPack.toString()+" bottles / pack" else "Not configured",fontWeight=FontWeight.SemiBold,color=if(p.bottlesPerPack>0)Navy else Color(0xFF9B5B00))};TextButton(onClick={editingProduct=p;dialog="product"}){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(4.dp));Text("Edit")};if(p.active)TextButton(onClick={scope.launch{runCatching{dao.updateProductWithHistory(p.id,p.name,p.sizeLabel,p.colour,p.bottlesPerPack,false,session?.uid?:"local","Product archived",session?.organisationId?:"local-org")}.onSuccess{message="Product archived with history"}.onFailure{message=it.message}}}){Text("Archive",color=Color(0xFFB45309))}}
     }
    }
   }
  }
 }
 PageTab.PRODUCTION->{
  val todayRows=production.filter{it.productionDate==date}
  val total=todayRows.sumOf{it.producedBottles};val goodToday=todayRows.sumOf{it.goodBottles};val rejectToday=todayRows.sumOf{it.rejectedBottles}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("SHOP FLOOR",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Production",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Record and review each production run",fontSize=12.sp,color=Muted)};Button(onClick={dialog="production"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("New run")}}}
   item{Surface(color=Navy,shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(17.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("TODAY'S OUTPUT",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.2.sp);Text(total.toString()+" bottles",fontSize=30.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);LinearProgressIndicator(progress={if(total>0)goodToday.toFloat()/total else 0f},modifier=Modifier.fillMaxWidth().height(7.dp),color=Color(0xFF35C38B),trackColor=Color(0xFF35516E));Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text("Good  $goodToday",fontSize=12.sp,color=Color.White);Text("Rejected  $rejectToday",fontSize=12.sp,color=Color(0xFFFFC56B))}}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Production runs",todayRows.size.toString(),Modifier.weight(1f));Metric("Reject rate",String.format(Locale.US,"%.1f%%",if(total>0)rejectToday*100.0/total else 0.0),Modifier.weight(1f))}}
   item{
    val productTotals=production.groupBy{it.productNameSnapshot}.mapValues{(_,rows)->rows.sumOf{it.producedBottles}}.entries.sortedByDescending{it.value}.take(4)
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Production by product",fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Total recorded bottles · all saved runs",fontSize=10.sp,color=Muted)};Icon(Icons.Default.BarChart,null,tint=Blue)}
      if(productTotals.isEmpty())Text("Chart will appear when production is recorded.",fontSize=12.sp,color=Muted)
      else{
       val maxOutput=productTotals.maxOf{it.value}.coerceAtLeast(1)
       productTotals.forEach{entry->
        Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
         Row(verticalAlignment=Alignment.CenterVertically){Text(entry.key,Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy);Text(String.format(Locale.US,"%,d",entry.value),fontSize=12.sp,fontWeight=FontWeight.Bold,color=Blue)}
         LinearProgressIndicator(progress={entry.value.toFloat()/maxOutput.toFloat()},modifier=Modifier.fillMaxWidth().height(7.dp),color=Color(0xFF1683FF),trackColor=Color(0xFFE6EEF7),strokeCap=androidx.compose.ui.graphics.StrokeCap.Round)
        }
       }
      }
     }
    }
   }
   item{Text("Production history",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   item{OutlinedTextField(productionSearch,{productionSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(15.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search product, machine or date")},label={Text("Filter production runs")})}
   val shownProduction=production.filter{(it.productNameSnapshot+" "+it.machineName+" "+it.productionDate+" "+it.remarks).contains(productionSearch,true)}
   item{Text(shownProduction.size.toString()+" matching production run(s)",fontSize=11.sp,color=Muted)}
   if(shownProduction.isEmpty())item{EmptyDashboardState(if(production.isEmpty())"No production recorded" else "No matching production runs",if(production.isEmpty())"Create a production run to start tracking output." else "Change the search text to find another run.",Icons.Default.Build)}
   items(shownProduction,key={it.id}){entry->
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Build,null,tint=Blue,modifier=Modifier.padding(10.dp).size(22.dp))};Column(Modifier.weight(1f).padding(start=10.dp)){Text(entry.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(entry.productionDate+" · "+entry.machineName,fontSize=11.sp,color=Muted)};Text("SAVED",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color(0xFF16804A))}
      HorizontalDivider(color=Border)
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Produced",entry.producedBottles,Modifier.weight(1f));StockMiniMetric("Good",entry.goodBottles,Modifier.weight(1f));StockMiniMetric("Rejected",entry.rejectedBottles,Modifier.weight(1f))}
      if(entry.producedBottles>0){val rate=entry.rejectedBottles*100.0/entry.producedBottles;Text(String.format(Locale.US,"Reject rate %.1f%%",rate),fontSize=11.sp,color=if(rate>=5)Color(0xFFB42318) else Muted)}
      if(entry.remarks.isNotBlank())Text(entry.remarks,fontSize=12.sp,color=Muted)
     }
    }
   }
  }
 }
 PageTab.PACKING->{
  val packedToday=packing.filter{it.packingDate==date}
  val bottleCount=packedToday.sumOf{it.bottlesConsumed};val packCount=packedToday.sumOf{it.completePacks};val looseCount=packedToday.sumOf{it.looseBottles}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("FINISHING LINE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Packing",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Convert good bottles into finished packs",fontSize=12.sp,color=Muted)};Button(onClick={dialog="packing"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("New batch")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Packs today",packCount.toString(),Modifier.weight(1f));Metric("Bottles used",bottleCount.toString(),Modifier.weight(1f))}}
   item{Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(17.dp)){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Info,null,tint=Blue);Column(Modifier.weight(1f).padding(start=10.dp)){Text("Pack calculation",fontWeight=FontWeight.Bold,color=Navy);Text("The configured product pack size determines complete packs and remaining loose bottles.",fontSize=11.sp,color=Muted)};Column(horizontalAlignment=Alignment.End){Text(looseCount.toString(),fontSize=20.sp,fontWeight=FontWeight.Bold,color=Blue);Text("loose today",fontSize=9.sp,color=Muted)}}}}
   item{Text("Packing history",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   if(packing.isEmpty())item{EmptyDashboardState("No packing batches yet","Save a packing batch after production has good bottles available.",Icons.Default.Inventory2)}
   items(packing,key={it.id}){entry->
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Inventory2,null,tint=Blue,modifier=Modifier.padding(10.dp).size(22.dp))};Column(Modifier.weight(1f).padding(start=10.dp)){Text(entry.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(entry.packingDate,fontSize=11.sp,color=Muted)};Surface(color=Color(0xFFE8F7F0),shape=RoundedCornerShape(30.dp)){Text("RECORDED",modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color(0xFF16804A))}}
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Bottles used",entry.bottlesConsumed,Modifier.weight(1f));StockMiniMetric("Complete packs",entry.completePacks,Modifier.weight(1f));StockMiniMetric("Loose",entry.looseBottles,Modifier.weight(1f))}
      if(session?.role?.let{it in setOf("owner","admin","manager")}==true)TextButton(onClick={reversalPackingId=entry.id;dialog="reversePacking"}){Icon(Icons.Default.Undo,null);Spacer(Modifier.width(4.dp));Text("Request reversal")}
     }
    }
   }
  }
 }
 PageTab.DISPATCH->{
  val posted=dispatchBatches.filter{it.status=="POSTED"}
  val shownBatches=dispatchBatches.filter{(it.customerNameSnapshot+" "+it.dispatchReference+" "+it.dispatchDate+" "+it.status).contains(dispatchSearch,true)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("OUTBOUND LOGISTICS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Dispatch",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Customer deliveries and finished-goods movement",fontSize=12.sp,color=Muted)};Button(onClick={dialog="dispatch"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("New")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Posted",posted.size.toString(),Modifier.weight(1f));Metric("Total packs",dispatchLines.filter{it.batchId in posted.map{b->b.id}}.sumOf{it.packs}.plus(dispatches.sumOf{it.packsDispatched}).toString(),Modifier.weight(1f))}}
   item{OutlinedTextField(dispatchSearch,{dispatchSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(15.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search customer, reference or status")})}
   item{Text("Dispatch history",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   if(shownBatches.isEmpty()&&dispatches.isEmpty())item{EmptyDashboardState("No dispatch records","Post a customer dispatch to record finished stock leaving the factory.",Icons.Default.LocalShipping)}
   items(shownBatches,key={it.id}){batch->
    val lines=dispatchLines.filter{it.batchId==batch.id}
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.LocalShipping,null,tint=Blue,modifier=Modifier.padding(10.dp).size(22.dp))};Column(Modifier.weight(1f).padding(start=10.dp)){Text(batch.customerNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(batch.dispatchReference,fontSize=11.sp,color=Blue)};Surface(color=if(batch.status=="POSTED")Color(0xFFE8F7F0) else Color(0xFFFFF4DF),shape=RoundedCornerShape(30.dp)){Text(batch.status,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(batch.status=="POSTED")Color(0xFF16804A) else Color(0xFF9B5B00))}}
      HorizontalDivider(color=Border)
      lines.forEach{line->Row(verticalAlignment=Alignment.CenterVertically){Text(line.productNameSnapshot,Modifier.weight(1f),fontSize=12.sp,color=Navy);Text(line.packs.toString()+" packs",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Blue)}}
      Row(verticalAlignment=Alignment.CenterVertically){Text(batch.dispatchDate+" · "+if(batch.remarks.isBlank())"No remarks" else batch.remarks,Modifier.weight(1f),fontSize=10.sp,color=Muted);if(batch.status=="POSTED"&&session?.role?.let{it in setOf("owner","admin","manager")}==true)TextButton(onClick={reversalBatchId=batch.id;dialog="reverse"}){Icon(Icons.Default.Undo,null);Text("Reverse")}}
     }
    }
   }
   if(dispatches.isNotEmpty())item{Text("Legacy dispatch records",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)}
   items(dispatches,key={it.id}){entry->Surface(color=Color.White,shape=RoundedCornerShape(15.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(13.dp)){Text(entry.customerName,fontWeight=FontWeight.Bold,color=Navy);Text(entry.productNameSnapshot+" · "+entry.packsDispatched+" packs",fontSize=12.sp,color=Blue);Text(entry.dispatchDate+" · "+entry.dispatchReference,fontSize=10.sp,color=Muted)}}}
  }
 }
 PageTab.ELECTRICITY->{
  val sorted=meters.sortedByDescending{it.readingDate}
  val totalUnits=meters.filter{it.readingDate.startsWith(date.substring(0,7))}.sumOf{it.consumption}
  val latest=sorted.firstOrNull()
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("ENERGY MONITORING",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Electricity",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Meter readings and consumption history",fontSize=12.sp,color=Muted)};Button(onClick={dialog="meter"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("Reading")}}}
   item{Surface(color=Navy,shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("CURRENT MONTH",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.3.sp);Text(String.format(Locale.US,"%.1f units",totalUnits),fontSize=31.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);Text("Total from saved meter readings",fontSize=11.sp,color=Color(0xFFD4E2F0));HorizontalDivider(color=Color(0xFF345574));Text(if(latest!=null)"Latest: "+latest.meterId+" · "+latest.readingDate else "No meter readings recorded yet",fontSize=12.sp,color=Color.White)}}}
   item{Text("Reading history",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   if(sorted.isEmpty())item{EmptyDashboardState("No meter readings yet","Add a start and end reading to calculate electricity usage.",Icons.Default.Bolt)}
   items(sorted,key={it.id}){entry->Surface(color=Color.White,shape=RoundedCornerShape(17.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Row(verticalAlignment=Alignment.CenterVertically){Surface(color=Color(0xFFFFF4DF),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Bolt,null,tint=Color(0xFF9B5B00),modifier=Modifier.padding(10.dp).size(22.dp))};Column(Modifier.weight(1f).padding(start=10.dp)){Text(entry.meterId,fontWeight=FontWeight.Bold,color=Navy);Text(entry.readingDate,fontSize=11.sp,color=Muted)};Column(horizontalAlignment=Alignment.End){Text(String.format(Locale.US,"%.1f",entry.consumption),fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("units",fontSize=10.sp,color=Muted)}};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Start",entry.startReading.toInt(),Modifier.weight(1f));StockMiniMetric("End",entry.endReading.toInt(),Modifier.weight(1f));StockMiniMetric("Used",entry.consumption.toInt(),Modifier.weight(1f))};if(entry.remarks.isNotBlank())Text(entry.remarks,fontSize=11.sp,color=Muted)}}}
  }
 }
 PageTab.CUSTOMERS->{
  val shown=customers.filter{(it.name+" "+it.phone+" "+it.address+" "+it.gstNumber).contains(customerSearch,true)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CUSTOMER DIRECTORY",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Customers",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(customers.size.toString()+" saved customer records",fontSize=12.sp,color=Muted)};Button(onClick={dialog="customer"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("Add")}}}
   item{OutlinedTextField(customerSearch,{customerSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(15.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search name, phone, address or GST")})}
   if(shown.isEmpty())item{EmptyDashboardState("No customers found","Add a customer before posting a dispatch.",Icons.Default.People)}
   items(shown,key={it.id}){customer->
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
      Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(15.dp)){Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){Text(customer.name.trim().take(1).uppercase(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Blue)}}
      Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(customer.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=15.sp);Text(customer.phone.ifBlank{"Phone not provided"},fontSize=12.sp,color=Blue);if(customer.address.isNotBlank())Text(customer.address,fontSize=11.sp,color=Muted);if(customer.gstNumber.isNotBlank())Text("GST · "+customer.gstNumber,fontSize=10.sp,color=Muted)}
      Column(horizontalAlignment=Alignment.End){TextButton(onClick={editingCustomer=customer;dialog="customerEdit"}){Icon(Icons.Default.Edit,null);Text("Edit")};TextButton(onClick={dialog="dispatch"}){Text("Dispatch")}}
     }
    }
   }
  }
 }
 PageTab.STOCK->{
  val totalPacked=packing.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.completePacks}}
  val legacyDispatched=dispatches.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.packsDispatched}}
  val validBatchIds=dispatchBatches.filter{it.status=="POSTED"}.map{it.id}.toSet()
  val batchedDispatched=dispatchLines.filter{it.batchId in validBatchIds}.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.packs}}
  val totalAvailable=active.sumOf{((totalPacked[it.id]?:0)-(legacyDispatched[it.id]?:0)-(batchedDispatched[it.id]?:0)).coerceAtLeast(0)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Text("INVENTORY CONTROL",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Finished stock",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Packed goods ready for dispatch",fontSize=12.sp,color=Muted)}
   item{Surface(color=Navy,shape=RoundedCornerShape(22.dp)){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("TOTAL AVAILABLE PACKS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.2.sp);Text(totalAvailable.toString(),fontSize=36.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);Text(active.size.toString()+" active product types",fontSize=12.sp,color=Color(0xFFD4E2F0))}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Packed",totalPacked.values.sum().toString(),Modifier.weight(1f));Metric("Dispatched",(legacyDispatched.values.sum()+batchedDispatched.values.sum()).toString(),Modifier.weight(1f))}}
   item{
    val configuredCount=active.count{it.bottlesPerPack>0}
    val lowCount=active.count{p->((totalPacked[p.id]?:0)-(legacyDispatched[p.id]?:0)-(batchedDispatched[p.id]?:0)).coerceAtLeast(0)<=5}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     Surface(Modifier.weight(1f),color=Color(0xFFE8F7F0),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(12.dp)){Text("Configured products",fontSize=10.sp,color=Muted);Text(configuredCount.toString()+" / "+active.size,fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFF16804A))}}
     Surface(Modifier.weight(1f),color=if(lowCount>0)Color(0xFFFFF3DE) else Color(0xFFE8F7F0),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(12.dp)){Text("Low-stock alerts",fontSize=10.sp,color=Muted);Text(lowCount.toString(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=if(lowCount>0)Color(0xFF9B5B00) else Color(0xFF16804A))}}
    }
   }
   item{OutlinedTextField(stockSearch,{stockSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(15.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search product or bottle colour")},label={Text("Find stock")})}
   item{Text("Stock by product",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   val shownStock=active.filter{(it.name+" "+it.sizeLabel+" "+it.colour).contains(stockSearch,true)}
   if(shownStock.isEmpty())item{EmptyDashboardState("No matching stock","Try another product name, size or colour.",Icons.Default.Search)}
   items(shownStock,key={it.id}){p->
    val packedCount=totalPacked[p.id]?:0
    val sent=(legacyDispatched[p.id]?:0)+(batchedDispatched[p.id]?:0)
    val available=(packedCount-sent).coerceAtLeast(0)
    val configured=p.bottlesPerPack>0
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
       Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.WaterDrop,null,tint=Cyan,modifier=Modifier.padding(11.dp).size(24.dp))}
       Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=16.sp);Text(if(configured)p.bottlesPerPack.toString()+" bottles per pack" else "Pack size not configured",fontSize=11.sp,color=if(configured)Muted else Color(0xFF9B5B00))}
       Column(horizontalAlignment=Alignment.End){Text(available.toString(),fontSize=24.sp,fontWeight=FontWeight.ExtraBold,color=if(available<=5)Color(0xFFB45309) else Navy);Text("available packs",fontSize=9.sp,color=Muted)}
      }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Packed",packedCount,Modifier.weight(1f));StockMiniMetric("Dispatched",sent,Modifier.weight(1f));StockMiniMetric("Loose bottles",packing.filter{it.productId==p.id}.sumOf{it.looseBottles},Modifier.weight(1f))}
      if(!configured)Text("Configure pack size in Products before posting packing or dispatch.",fontSize=11.sp,color=Color(0xFF9B5B00))
     }
    }
   }
  }
 }
 PageTab.MACHINES->{
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("SHOP-FLOOR ASSETS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Machine management",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(machines.size.toString()+" registered machines",fontSize=12.sp,color=Muted)};Button(onClick={dialog="machine"},shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.Add,null);Text(" Add")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Running",machines.count{it.status=="RUNNING"}.toString(),Modifier.weight(1f));Metric("Stopped",machines.count{it.status!="RUNNING"}.toString(),Modifier.weight(1f))}}
   if(machines.isEmpty())item{EmptyDashboardState("No machines registered","Add your filling, capping, labelling or packing machines.",Icons.Default.Settings)}
   items(machines,key={it.id}){m->Surface(color=Color.White,shape=RoundedCornerShape(17.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Settings,null,tint=Blue,modifier=Modifier.padding(10.dp))};Column(Modifier.weight(1f).padding(start=10.dp)){Text(m.name,fontWeight=FontWeight.Bold,color=Navy);Text(m.machineCode+" · "+m.machineType,fontSize=11.sp,color=Muted);if(m.location.isNotBlank())Text(m.location,fontSize=11.sp,color=Muted)};Text(m.status,fontSize=10.sp,fontWeight=FontWeight.Bold,color=if(m.status=="RUNNING")Color(0xFF16804A) else Color(0xFFB45309))}
    Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Button(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot change machine status"};val now=System.currentTimeMillis();val next=if(m.status=="RUNNING")"STOPPED" else "RUNNING";if(next=="STOPPED"){val eventId=dao.insertMachineEvent(MachineEventEntity(machineId=m.id,machineNameSnapshot=m.name,eventType="MACHINE_STOPPED",startedAt=now,reason="Operator stopped machine",status="OPEN",performedBy=session?.uid?:"local"));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-stop:"+eventId,organisationId=session?.organisationId?:"local-org",eventType="MACHINE_STOPPED",payloadJson=JSONObject().put("machineId",m.id).put("eventId",eventId).toString()))}else{machineEvents.filter{it.machineId==m.id&&it.eventType=="MACHINE_STOPPED"&&it.status=="OPEN"}.maxByOrNull{it.startedAt}?.let{dao.closeMachineEvent(it.id)};val eventId=dao.insertMachineEvent(MachineEventEntity(machineId=m.id,machineNameSnapshot=m.name,eventType="MACHINE_STARTED",startedAt=now,endedAt=now,reason="Operator restarted machine",status="CLOSED",performedBy=session?.uid?:"local"));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-start:"+eventId,organisationId=session?.organisationId?:"local-org",eventType="MACHINE_STARTED",payloadJson=JSONObject().put("machineId",m.id).put("eventId",eventId).toString()))};dao.updateMachineStatus(m.id,next);dao.insertAudit(AuditEventEntity(eventType="MACHINE_STATUS_CHANGED",entityType="machine",entityId=m.id.toString(),beforeJson=m.status,afterJson=next,reason="Status changed from machine screen",actorId=session?.uid?:"local"));message="Machine status updated"}.onFailure{message=it.message}}},modifier=Modifier.weight(1f),shape=RoundedCornerShape(11.dp)){Text(if(m.status=="RUNNING")"Stop machine" else "Mark running")}
     OutlinedButton(onClick={dialog="machineEvent";selectedMachineId=m.id},modifier=Modifier.weight(1f),shape=RoundedCornerShape(11.dp)){Text("Log downtime")}
     OutlinedButton(onClick={dialog="maintenance";selectedMachineId=m.id},modifier=Modifier.weight(1f),shape=RoundedCornerShape(11.dp)){Text("Maintenance")}
    }
   }}}
   item{Text("Downtime & maintenance history",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)}
   items(machineEvents,key={it.id}){e->Surface(color=Color.White,shape=RoundedCornerShape(13.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(e.machineNameSnapshot+" · "+e.eventType,fontWeight=FontWeight.Bold,color=Navy);Text(e.reason.ifBlank{e.maintenanceType},fontSize=12.sp,color=Muted);Row(verticalAlignment=Alignment.CenterVertically){Text(if(e.status=="OPEN")"OPEN" else "CLOSED",Modifier.weight(1f),fontSize=10.sp,fontWeight=FontWeight.Bold,color=if(e.status=="OPEN")Color(0xFFB45309) else Color(0xFF16804A));if(e.status=="OPEN")TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot close machine events"};require(dao.closeMachineEvent(e.id)==1){"Event already closed"};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-event-closed:"+e.id,organisationId=session?.organisationId?:"local-org",eventType="MACHINE_EVENT_CLOSED",payloadJson=JSONObject().put("eventId",e.id).put("machineId",e.machineId).toString()));val machine=machines.firstOrNull{it.id==e.machineId};if(machine!=null&&machine.status in setOf("DOWN","MAINTENANCE"))dao.updateMachineStatus(machine.id,"RUNNING");dao.insertAudit(AuditEventEntity(eventType="MACHINE_EVENT_CLOSED",entityType="machine_event",entityId=e.id.toString(),reason="Machine event closed",actorId=session?.uid?:"local"));message="Machine event closed"}.onFailure{message=it.message}}}){Text("Close event")}}}}}
  }
 }
 PageTab.QUALITY->{
  val inspected=qualityInspections.sumOf{it.inspectedBottles};val failed=qualityInspections.sumOf{it.failedBottles}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("QUALITY CONTROL",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Inspections",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Defects, inspection results and rejection trends",fontSize=12.sp,color=Muted)};Button(onClick={dialog="quality"},shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.Add,null);Text(" Inspect")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Inspected",inspected.toString(),Modifier.weight(1f));Metric("Failed",failed.toString(),Modifier.weight(1f));Metric("Failure rate",String.format(Locale.US,"%.1f%%",if(inspected>0)failed*100.0/inspected else 0.0),Modifier.weight(1f))}}
   if(qualityInspections.isEmpty())item{EmptyDashboardState("No quality inspections","Log sample inspections with defect categories to start batch quality history.",Icons.Default.Verified)}
   items(qualityInspections,key={it.id}){q->Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(q.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(q.inspectionDate+" · "+q.defectCategory,fontSize=11.sp,color=Muted)};Text(q.severity,fontSize=10.sp,fontWeight=FontWeight.Bold,color=if(q.severity=="CRITICAL")Color(0xFFB42318) else Color(0xFF9B5B00))};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Inspected",q.inspectedBottles,Modifier.weight(1f));StockMiniMetric("Passed",q.passedBottles,Modifier.weight(1f));StockMiniMetric("Failed",q.failedBottles,Modifier.weight(1f))};if(q.productionEntryId!=null)Text("Production entry #"+q.productionEntryId,fontSize=10.sp,color=Blue);if(q.notes.isNotBlank())Text(q.notes,fontSize=12.sp,color=Muted)}}}
  }
 }
 PageTab.PLANNING->{
  val todayPlans=plans.filter{it.planDate==date};val target=todayPlans.sumOf{it.targetBottles};val actual=todayPlans.sumOf{plan->production.filter{it.productionDate==date&&it.productId==plan.productId}.sumOf{it.producedBottles}}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("DAILY OPERATIONS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Production planning",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Targets by date, shift and machine",fontSize=12.sp,color=Muted)};Button(onClick={dialog="plan"},shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.Add,null);Text(" Plan")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Today's target",target.toString(),Modifier.weight(1f));Metric("Produced today",actual.toString(),Modifier.weight(1f));Metric("Gap",(target-actual).coerceAtLeast(0).toString(),Modifier.weight(1f))}}
   if(plans.isEmpty())item{EmptyDashboardState("No production plans","Set bottle targets for each shift and product.",Icons.Default.EventNote)}
   items(plans,key={it.id}){p->val done=production.filter{it.productionDate==p.planDate&&it.productId==p.productId}.sumOf{it.producedBottles};Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(p.planDate+" · "+p.shift,fontSize=11.sp,color=Muted)};Text(p.status,fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue)};Row(verticalAlignment=Alignment.CenterVertically){Text("Target "+p.targetBottles,Modifier.weight(1f),fontSize=12.sp,color=Muted);Text("Actual "+done,fontSize=12.sp,fontWeight=FontWeight.Bold,color=Navy)};LinearProgressIndicator(progress={if(p.targetBottles>0)(done.toFloat()/p.targetBottles).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(7.dp),color=Color(0xFF16804A),trackColor=Page);if(p.status=="PLANNED")TextButton(onClick={scope.launch{runCatching{dao.updateProductionPlanStatus(p.id,"IN_PROGRESS");message="Plan marked in progress"}.onFailure{message=it.message}}}){Text("Start plan")};if(p.status=="IN_PROGRESS")TextButton(onClick={scope.launch{runCatching{dao.updateProductionPlanStatus(p.id,"COMPLETED");message="Plan completed"}.onFailure{message=it.message}}}){Text("Mark completed")}}}}
  }
 }
 PageTab.MOVEMENTS->{
  val shown=inventoryMovements.filter{(it.productNameSnapshot+" "+it.movementType+" "+it.sourceType+" "+it.sourceId+" "+it.note+" "+it.actorId).contains(movementSearch,true)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Text("INVENTORY LEDGER",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Stock movement history",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Every recorded stock movement with source reference",fontSize=12.sp,color=Muted)}
   item{OutlinedTextField(movementSearch,{movementSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search product, movement or source")})}
   item{Text(shown.size.toString()+" movement(s)",fontSize=11.sp,color=Muted)}
   if(shown.isEmpty())item{EmptyDashboardState("No stock movements found","Movements appear when production, packing, dispatch or reversals post inventory changes.",Icons.Default.SwapVert)}
   items(shown,key={it.id}){m->Surface(color=Color.White,shape=RoundedCornerShape(15.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(m.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(m.movementType+" · "+m.sourceType+" #"+m.sourceId,fontSize=11.sp,color=Muted)};Text((if(m.quantity>0)"+" else "")+m.quantity+" "+m.unit,fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=if(m.quantity>=0)Color(0xFF16804A) else Color(0xFFB42318))};Text("By "+m.actorId+" · "+SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.US).format(Date(m.createdAt)),fontSize=10.sp,color=Muted);if(m.note.isNotBlank())Text(m.note,fontSize=11.sp,color=Muted)}}}
  }
 }
 PageTab.ORDERS->{
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CUSTOMER FULFILMENT",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Sales orders",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Order quantities linked to dispatch fulfilment",fontSize=12.sp,color=Muted)};Button(onClick={dialog="order"},shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.Add,null);Text(" Order")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Orders",orders.size.toString(),Modifier.weight(1f));Metric("Open",orders.count{it.status=="OPEN"||it.status=="PARTIAL"}.toString(),Modifier.weight(1f));Metric("Fulfilled",orders.count{it.status=="FULFILLED"}.toString(),Modifier.weight(1f))}}
   if(orders.isEmpty())item{EmptyDashboardState("No sales orders","Create a customer order with product and pack quantities.",Icons.Default.ReceiptLong)}
   items(orders,key={it.id}){o->val lines=orderLines.filter{it.orderId==o.id};val ordered=lines.sumOf{it.packsOrdered};val fulfilled=lines.sumOf{it.packsDispatched};Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(o.orderNumber,fontWeight=FontWeight.ExtraBold,color=Navy);Text(o.customerNameSnapshot,fontSize=12.sp,color=Muted);Text("Due "+o.dueDate,fontSize=11.sp,color=Muted)};Text(o.status,fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue)};Row(verticalAlignment=Alignment.CenterVertically){Text("Fulfilled $fulfilled / $ordered packs",Modifier.weight(1f),fontSize=12.sp,color=Navy);TextButton(onClick={selectedOrderId=o.id;dialog="fulfilOrder"}){Text("Update fulfilment")}};if(lines.isNotEmpty())LinearProgressIndicator(progress={if(ordered>0)fulfilled.toFloat()/ordered else 0f},modifier=Modifier.fillMaxWidth().height(7.dp),color=Color(0xFF16804A),trackColor=Page)}}}
  }
 }
 PageTab.STAFF->{
  val currentRole=session?.role?.let{runCatching{FactoryPermissions.fromClaim(it)}.getOrNull()}
  val roles=FactoryRole.values().toList()
  val actions=FactoryAction.values().toList()
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Text("ACCESS GOVERNANCE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Staff & permissions",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Role-based access matrix used by the app",fontSize=12.sp,color=Muted)}
   item{Surface(color=Navy,shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("CURRENT SESSION",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.sp);Text(if(session!=null)session!!.role.uppercase() else "LOCAL DEVICE MODE",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);Text(if(session!=null)"Organisation: "+session!!.organisationId else "No cloud identity loaded. Local mode should be used only on a trusted device.",fontSize=11.sp,color=Color(0xFFD4E2F0))}}}
   item{Text("Role capability reference",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Cloud staff roles are resolved from trusted Firebase custom claims. This screen is read-only; staff assignments must be changed by an authorised administrator.",fontSize=12.sp,color=Muted)}
   items(roles){role->Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(role==currentRole)Blue else Border)){Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(role.name.replace("_"," "),Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Navy);if(role==currentRole)Text("CURRENT ROLE",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Blue)};val permitted=actions.filter{FactoryPermissions.allows(role,it)};Text("Allowed actions",fontSize=10.sp,color=Muted);Text(permitted.joinToString(" · "){it.name.lowercase().replace("_"," ")},fontSize=11.sp,color=Navy);Text("Restricted: "+actions.filter{!FactoryPermissions.allows(role,it)}.joinToString(" · "){it.name.lowercase().replace("_"," ")},fontSize=10.sp,color=Color(0xFF9B5B00))}}}
  }
 }
 PageTab.AUDIT->{
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{Text("GOVERNANCE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Audit history",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Recorded changes, reversals and operational actions",fontSize=12.sp,color=Muted)}
   item{Metric("Audit events",auditEvents.size.toString(),Modifier.fillMaxWidth())}
   if(auditEvents.isEmpty())item{EmptyDashboardState("No audit events yet","Events will appear as users perform tracked operations.",Icons.Default.History)}
   items(auditEvents,key={it.id}){a->Surface(color=Color.White,shape=RoundedCornerShape(14.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(a.eventType,fontWeight=FontWeight.Bold,color=Navy);Text(a.entityType+" #"+a.entityId+" · "+a.reason,fontSize=11.sp,color=Muted);Text("Actor: "+a.actorId+" · "+SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.US).format(Date(a.createdAt)),fontSize=10.sp,color=Blue);if(a.beforeJson.isNotBlank()||a.afterJson.isNotBlank())Text("Before: "+a.beforeJson+"  →  After: "+a.afterJson,fontSize=10.sp,color=Muted)}}}
  }
 }
 PageTab.REPORTS->{
  val totals=runCatching{ReportExport.totals(java.time.LocalDate.parse(reportFrom),java.time.LocalDate.parse(reportTo),production,packing,dispatches,meters,dispatchBatches,dispatchLines)}.getOrNull()
  val daily=production.filter{it.productionDate in reportFrom..reportTo}.groupBy{it.productionDate}.toSortedMap().mapValues{(_,rows)->rows.sumOf{it.producedBottles}}
  val maxDay=(daily.values.maxOrNull()?:0).coerceAtLeast(1)
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("FACTORY INTELLIGENCE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Reports",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Performance from saved factory records",fontSize=12.sp,color=Muted)};IconButton(onClick={exportLauncher.launch("tck-factoryflow-report-"+date+".csv")}){Icon(Icons.Default.FileDownload,null,tint=Blue)}}}
   item{Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("REPORT PERIOD",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.1.sp);OutlinedTextField(reportFrom,{reportFrom=it},label={Text("From date · yyyy-MM-dd")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp));OutlinedTextField(reportTo,{reportTo=it},label={Text("To date · yyyy-MM-dd")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp));Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton(onClick={reportFrom=date;reportTo=date}){Text("Today")};TextButton(onClick={reportFrom=java.time.LocalDate.parse(date).withDayOfMonth(1).toString();reportTo=date}){Text("This month")};TextButton(onClick={reportTo=date;reportFrom=java.time.LocalDate.parse(date).minusDays(6).toString()}){Text("7 days")}};Button(onClick={exportLauncher.launch("tck-factoryflow-"+reportFrom+"-to-"+reportTo+".csv")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.FileDownload,null);Spacer(Modifier.width(6.dp));Text("Export selected period")}}}}}
   if(totals==null)item{Surface(color=Color(0xFFFFF4DF),shape=RoundedCornerShape(14.dp)){Text("Enter valid dates using yyyy-MM-dd. The end date must be on or after the start date.",Modifier.padding(14.dp),color=Color(0xFF9B5B00),fontSize=12.sp)}} else {
    item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Produced",totals.produced.toString(),Modifier.weight(1f));Metric("Good bottles",totals.good.toString(),Modifier.weight(1f))}}
    item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Rejected",totals.rejected.toString(),Modifier.weight(1f));Metric("Reject rate",String.format(Locale.US,"%.1f%%",if(totals.produced>0)totals.rejected*100.0/totals.produced else 0.0),Modifier.weight(1f))}}
    item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Complete packs",totals.completePacks.toString(),Modifier.weight(1f));Metric("Dispatched packs",totals.dispatchedPacks.toString(),Modifier.weight(1f))}}
    item{Metric("Electricity consumption",String.format(Locale.US,"%.1f units",totals.electricity),Modifier.fillMaxWidth())}
   }
   item{Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Daily production",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Bottles produced per day · selected period",fontSize=11.sp,color=Muted);if(daily.isEmpty())Text("No production entries in this period.",fontSize=12.sp,color=Muted) else daily.entries.toList().takeLast(10).forEach{(day,value)->Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(day,Modifier.weight(1f),fontSize=10.sp,color=Muted);Text(value.toString(),fontSize=11.sp,fontWeight=FontWeight.Bold,color=Navy)};LinearProgressIndicator(progress={value.toFloat()/maxDay},modifier=Modifier.fillMaxWidth().height(6.dp),color=Cyan,trackColor=Page,strokeCap=androidx.compose.ui.graphics.StrokeCap.Round)}}}}}
   item{
    val productRows=production.filter{it.productionDate in reportFrom..reportTo}.groupBy{it.productNameSnapshot}.mapValues{(_,rows)->Triple(rows.sumOf{it.producedBottles},rows.sumOf{it.goodBottles},rows.sumOf{it.rejectedBottles})}.entries.sortedByDescending{it.value.first}.take(6)
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Product performance",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Output and quality · selected period",fontSize=11.sp,color=Muted)};Icon(Icons.Default.Analytics,null,tint=Blue)}
      if(productRows.isEmpty())Text("No production data for this period.",fontSize=12.sp,color=Muted)
      else productRows.forEach{entry->
       val produced=entry.value.first;val good=entry.value.second;val rejected=entry.value.third
       Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text(entry.key,Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy);Text(String.format(Locale.US,"%,d bottles",produced),fontSize=11.sp,fontWeight=FontWeight.Bold,color=Navy)}
        Row(Modifier.fillMaxWidth().height(8.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)){
         if(good>0)Box(Modifier.weight(good.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(Color(0xFF29B985),RoundedCornerShape(4.dp)))
         if(rejected>0)Box(Modifier.weight(rejected.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(Color(0xFFE05B70),RoundedCornerShape(4.dp)))
        }
        Row(verticalAlignment=Alignment.CenterVertically){Text("Good "+good,Modifier.weight(1f),fontSize=10.sp,color=Color(0xFF16804A));Text("Rejected "+rejected,fontSize=10.sp,color=Color(0xFFB42318));Text(String.format(Locale.US," · %.1f%%",if(produced>0)rejected*100.0/produced else 0.0),fontSize=10.sp,color=Muted)}
       }
      }
      Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){Box(Modifier.size(8.dp).background(Color(0xFF29B985),RoundedCornerShape(3.dp)));Text("Good bottles",fontSize=10.sp,color=Muted)};Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){Box(Modifier.size(8.dp).background(Color(0xFFE05B70),RoundedCornerShape(3.dp)));Text("Rejected bottles",fontSize=10.sp,color=Muted)}}
     }
    }
   }
   item{val prior=runCatching{val from=java.time.LocalDate.parse(reportFrom);val to=java.time.LocalDate.parse(reportTo);val days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;val priorTo=from.minusDays(1);val priorFrom=priorTo.minusDays(days-1);ReportExport.totals(priorFrom,priorTo,production,packing,dispatches,meters,dispatchBatches,dispatchLines)}.getOrNull();if(prior!=null&&totals!=null)Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("Previous equal-length period",fontWeight=FontWeight.Bold,color=Navy);Text("Produced "+prior.produced+" · Packed "+prior.completePacks+" packs · Dispatched "+prior.dispatchedPacks+" packs",fontSize=11.sp,color=Blue);Text("Electricity "+String.format(Locale.US,"%.1f units",prior.electricity),fontSize=11.sp,color=Blue)}}}
  }
 }
 }
 message?.let{Text(it,Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color.White,RoundedCornerShape(12.dp)).padding(12.dp),color=Blue)}
 }
 }
 }
 if(dialog=="reverse"&&reversalBatchId!=null)ReverseDispatchForm({dialog="";reversalBatchId=null}){reason->scope.launch{runCatching{dao.reverseDispatch(reversalBatchId!!,reason,session?.uid?:"local",session?.organisationId?:"local-org");"Dispatch reversed"}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalBatchId=null}
 if(dialog=="reversePacking"&&reversalPackingId!=null)ReversePackingForm({dialog="";reversalPackingId=null}){reason->scope.launch{runCatching{dao.reversePacking(reversalPackingId!!,reason,session?.uid?: "local",session?.organisationId?: "local-org");"Packing entry reversed"}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalPackingId=null}

 if(dialog=="machine")MachineForm({dialog=""}){code,name,type,location,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot manage machines"};val machineId=dao.insertMachine(MachineEntity(machineCode=code,name=name,machineType=type,location=location,notes=notes));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-register:"+machineId,organisationId=session?.organisationId?:"local-org",eventType="MACHINE_REGISTERED",payloadJson=JSONObject().put("machineId",machineId).put("code",code).put("name",name).put("type",type).toString()));dao.insertAudit(AuditEventEntity(eventType="MACHINE_REGISTERED",entityType="machine",entityId=code,afterJson=name,reason="Machine registered",actorId=session?.uid?:"local"));message="Machine registered"}.onFailure{message=it.message}};dialog=""}
 if((dialog=="machineEvent"||dialog=="maintenance")&&selectedMachineId!=null){val machine=machines.firstOrNull{it.id==selectedMachineId};if(machine!=null)MachineEventForm(machine,dialog=="maintenance",{dialog="";selectedMachineId=null}){kind,reason->scope.launch{runCatching{val isMaintenance=dialog=="maintenance";require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot log machine events"};val eventId=dao.insertMachineEvent(MachineEventEntity(machineId=machine.id,machineNameSnapshot=machine.name,eventType=if(isMaintenance)"MAINTENANCE" else "DOWNTIME",startedAt=System.currentTimeMillis(),reason=reason,maintenanceType=if(isMaintenance)kind else "",status="OPEN",performedBy=session?.uid?:"local"));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-event:"+eventId,organisationId=session?.organisationId?:"local-org",eventType=if(isMaintenance)"MAINTENANCE_LOGGED" else "DOWNTIME_LOGGED",payloadJson=JSONObject().put("machineId",machine.id).put("reason",reason).toString()));dao.updateMachineStatus(machine.id,if(isMaintenance)"MAINTENANCE" else "DOWN");dao.insertAudit(AuditEventEntity(eventType=if(isMaintenance)"MAINTENANCE_LOGGED" else "DOWNTIME_LOGGED",entityType="machine",entityId=machine.id.toString(),reason=reason,actorId=session?.uid?:"local"));message=if(isMaintenance)"Maintenance logged" else "Downtime logged"}.onFailure{message=it.message}};dialog="";selectedMachineId=null}}
 if(dialog=="quality")QualityForm(active,production,{dialog=""}){product,productionEntry,d,i,p,failed,category,severity,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot create quality inspections"};require(i>0&&p in 0..i);val inspectionId=dao.insertQualityInspection(QualityInspectionEntity(productionEntryId=productionEntry?.id,productId=product.id,productNameSnapshot=product.name,inspectionDate=d,inspectedBottles=i,passedBottles=p,failedBottles=failed,defectCategory=category,severity=severity,notes=notes,inspectorId=session?.uid?:"local"));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="quality-inspection:"+inspectionId,organisationId=session?.organisationId?:"local-org",eventType="QUALITY_INSPECTION_SAVED",payloadJson=JSONObject().put("inspectionId",inspectionId).put("productId",product.id).put("inspected",i).put("passed",p).put("failed",failed).put("defect",category).put("severity",severity).toString()));dao.insertAudit(AuditEventEntity(eventType="QUALITY_INSPECTION_SAVED",entityType="quality",entityId=product.id.toString(),afterJson="inspection saved",reason="Quality inspection recorded",actorId=session?.uid?:"local"));message="Quality inspection saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="plan")ProductionPlanForm(active,machines,{dialog=""}){product,d,shift,target,machineId,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot create production plans"};val planId=dao.insertProductionPlan(ProductionPlanEntity(planDate=d,shift=shift,productId=product.id,productNameSnapshot=product.name,targetBottles=target,assignedMachineId=machineId,notes=notes,createdBy=session?.uid?:"local"));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan:"+planId,organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_CREATED",payloadJson=JSONObject().put("planId",planId).put("productId",product.id).put("date",d).put("shift",shift).put("target",target).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_CREATED",entityType="product",entityId=product.id.toString(),afterJson="plan saved",reason="Production plan created",actorId=session?.uid?:"local"));message="Production plan saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="order")SalesOrderForm(customers,active,{dialog=""}){customer,product,number,d,due,packs,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.POST_DISPATCH)){"Your role cannot create sales orders"};val id=dao.insertSalesOrder(SalesOrderEntity(orderNumber=number,customerId=customer.id,customerNameSnapshot=customer.name,orderDate=d,dueDate=due,notes=notes,createdBy=session?.uid?:"local"));dao.insertSalesOrderLines(listOf(SalesOrderLineEntity(orderId=id,productId=product.id,productNameSnapshot=product.name,packsOrdered=packs,packSizeSnapshot=product.bottlesPerPack)));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="sales-order:"+id,organisationId=session?.organisationId?:"local-org",eventType="SALES_ORDER_CREATED",payloadJson=JSONObject().put("orderId",id).put("orderNumber",number).put("customerId",customer.id).put("productId",product.id).put("packs",packs).toString()));dao.insertAudit(AuditEventEntity(eventType="SALES_ORDER_CREATED",entityType="sales_order",entityId=id.toString(),afterJson="order created",reason="Sales order created",actorId=session?.uid?:"local"));message="Sales order created"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="fulfilOrder"&&selectedOrderId!=null){val order=orders.firstOrNull{it.id==selectedOrderId};val lines=orderLines.filter{it.orderId==selectedOrderId};if(order!=null)FulfilOrderForm(order,lines,{dialog="";selectedOrderId=null}){line,qty->scope.launch{runCatching{require(roleAllows(session,FactoryAction.POST_DISPATCH)){"Your role cannot update order fulfilment"};require(dao.setOrderLineFulfilled(line.id,qty)==1){"Fulfilment quantity is invalid"};val all=lines.map{if(it.id==line.id)it.copy(packsDispatched=qty) else it};val status=when{all.all{it.packsDispatched>=it.packsOrdered}->"FULFILLED";all.any{it.packsDispatched>0}->"PARTIAL";else->"OPEN"};dao.updateSalesOrderStatus(order.id,status);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="order-fulfilment:"+order.id+":"+line.id+":"+qty,organisationId=session?.organisationId?:"local-org",eventType="ORDER_FULFILMENT_UPDATED",payloadJson=JSONObject().put("orderId",order.id).put("lineId",line.id).put("packsFulfilled",qty).put("status",status).toString()));dao.insertAudit(AuditEventEntity(eventType="ORDER_FULFILMENT_UPDATED",entityType="sales_order",entityId=order.id.toString(),afterJson="fulfilment updated",reason="Order fulfilment progress updated; dispatch posting remains a separate operation",actorId=session?.uid?:"local"));message="Order fulfilment updated"}.onFailure{message=it.message}};dialog="";selectedOrderId=null}}
 if(dialog=="login")LoginForm({dialog=""}){email,password->scope.launch{runCatching{FactoryAuthRepository(context).signIn(email,password)}.onSuccess{session=it;message="Signed in as "+it.role+" · "+it.organisationId}.onFailure{message=it.message}};dialog=""}
 if(dialog=="product")ProductForm(editingProduct,{dialog="";editingProduct=null}){n,s,c,p->val existing=editingProduct;scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot manage products"};if(existing==null)dao.insertProduct(ProductEntity(name=n,sizeLabel=s,colour=c,bottlesPerPack=p)) else dao.updateProductWithHistory(existing.id,n,s,c,p,existing.active,session?.uid?:"local","Product configuration edited",session?.organisationId?:"local-org")}.onSuccess{message="Product saved with configuration history"}.onFailure{message=it.message}};dialog="";editingProduct=null}
 if(dialog=="production")ProductionForm(active,{dialog=""}){p,d,total,good,rejected,machine,remarks->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot post production"};ProductionQuantities(total,good,rejected).validate();require(p!=null){"Choose a product"};dao.postProduction(ProductionEntryEntity(productId=p.id,productNameSnapshot=p.name,productionDate=d,producedBottles=total,goodBottles=good,rejectedBottles=rejected,machineName=machine,remarks=remarks),session?.organisationId?:"local-org",session?.uid?:"local")}.onSuccess{message="Production saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="packing")PackingForm(active,dao,{dialog=""}){p,d,n->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PACKING)){"Your role cannot post packing"};require(p!=null){"Choose a product"};val calc=PackCalculation.calculate(n,p.bottlesPerPack);dao.postPacking(PackingEntryEntity(productId=p.id,productNameSnapshot=p.name,packingDate=d,bottlesConsumed=n,completePacks=calc.completePacks,looseBottles=calc.looseBottles),session?.organisationId?:"local-org",session?.uid?:"local");"Saved "+calc.completePacks+" packs and "+calc.looseBottles+" loose bottles"}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
 if(dialog=="dispatch")DispatchBatchForm(active,customers,openOrders,null,{dialog=""}){customer,customerName,d,reference,remarks,lines,key,orderId->scope.launch{runCatching{require(roleAllows(session,FactoryAction.POST_DISPATCH)){"Your role cannot post dispatches"};val batch=DispatchBatchEntity(idempotencyKey=key,dispatchReference=reference.ifBlank{"DSP-"+System.currentTimeMillis()},dispatchDate=d,customerId=customer?.id,customerNameSnapshot=customerName,remarks=remarks,createdBy=session?.uid?:"local",salesOrderId=orderId);if(orderId!=null)dao.postSalesOrderDispatch(batch,lines,session?.organisationId?:"local-org",session?.uid?:"local") else dao.postMultiLineDispatch(batch,lines,session?.organisationId?:"local-org",session?.uid?:"local");"Dispatch saved"+if(orderId!=null)" and order fulfilment reconciled" else ""}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
 if(dialog=="customer")CustomerForm(null,{dialog=""}){name,phone,address,gst->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot manage customers"};dao.insertCustomer(CustomerEntity(name=name,phone=phone,address=address,gstNumber=gst))}.onSuccess{message="Customer saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="customerEdit")CustomerForm(editingCustomer,{dialog="";editingCustomer=null}){name,phone,address,gst->val existing=editingCustomer;scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot manage customers"};require(existing!=null);dao.updateCustomer(existing!!.id,name,phone,address,gst,existing.active)}.onSuccess{message="Customer details updated"}.onFailure{message=it.message}};dialog="";editingCustomer=null}
 if(dialog=="meter")MeterForm({dialog=""}){d,m,s,e,r->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot manage electricity readings"};val used=ElectricityRules.consumption(s,e);dao.insertMeterReading(MeterReadingEntity(meterId=m,readingDate=d,startReading=s,endReading=e,consumption=used,remarks=r));"Reading saved: "+used+" units"}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
}


@Composable private fun MachineForm(close:()->Unit,save:(String,String,String,String,String)->Unit){
 var code by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var type by remember{mutableStateOf("Filling")};var location by remember{mutableStateOf("")};var notes by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Register machine",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Give every machine a unique code so downtime and maintenance can be traced.",fontSize=12.sp,color=Muted);Field("Machine code *",code){code=it.uppercase().filter{c->c.isLetterOrDigit()||c=='-'}.take(24)};Field("Machine name *",name){name=it};Text("Machine type",fontSize=12.sp,color=Muted);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Filling","Capping","Labelling","Packing","Compressor","Other").forEach{t->FilterChip(selected=type==t,onClick={type=t},label={Text(t,fontSize=10.sp)})}};Field("Location / line",location){location=it};OutlinedTextField(notes,{notes=it},label={Text("Notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=code.isNotBlank()&&name.isNotBlank(),onClick={save(code.trim(),name.trim(),type,location.trim(),notes.trim())}){Text("Register machine")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun MachineEventForm(machine:MachineEntity,maintenance:Boolean,close:()->Unit,save:(String,String)->Unit){
 var reason by remember{mutableStateOf("")};var kind by remember{mutableStateOf(if(maintenance)"Preventive maintenance" else "Breakdown")};var notes by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text(if(maintenance)"Log maintenance" else "Log downtime",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){Text(machine.name+" · "+machine.machineCode,fontWeight=FontWeight.Bold,color=Blue);if(maintenance)Text("Maintenance type",fontSize=12.sp,color=Muted);if(maintenance)Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Preventive maintenance","Repair","Inspection","Lubrication").forEach{t->FilterChip(selected=kind==t,onClick={kind=t},label={Text(t,fontSize=10.sp)})}};Field(if(maintenance)"Work performed *" else "Downtime reason *",reason){reason=it};OutlinedTextField(notes,{notes=it},label={Text("Notes / parts used")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=reason.isNotBlank(),onClick={save(if(maintenance)kind else "Breakdown",reason.trim()+" "+notes.trim())}){Text("Save record")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun QualityForm(products:List<ProductEntity>,production:List<ProductionEntryEntity>,close:()->Unit,save:(ProductEntity,ProductionEntryEntity?,String,Int,Int,Int,String,String,String)->Unit){
 var product by remember{mutableStateOf(products.firstOrNull())};var productionEntry by remember{mutableStateOf<ProductionEntryEntity?>(null)};var date by remember{mutableStateOf(today())};var inspected by remember{mutableStateOf("")};var passed by remember{mutableStateOf("")};var category by remember{mutableStateOf("Cap / seal defect")};var severity by remember{mutableStateOf("MINOR")};var notes by remember{mutableStateOf("")}
 val i=inspected.toIntOrNull()?:0;val p=passed.toIntOrNull()?:0;val failed=i-p
 AlertDialog(onDismissRequest=close,title={Text("Quality inspection",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Link this inspection to a production batch where possible. Failed quantity is calculated automatically.",fontSize=12.sp,color=Muted);Text("Production batch (optional)",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy);production.take(4).forEach{entry->FilterChip(selected=productionEntry?.id==entry.id,onClick={productionEntry=entry;product=products.firstOrNull{it.id==entry.productId}?:product},label={Text(entry.productionDate+" · "+entry.productNameSnapshot+" · good "+entry.goodBottles,fontSize=10.sp)})};if(productionEntry!=null)TextButton(onClick={productionEntry=null}){Text("Remove batch link")};ProductPicker(products,product){product=it;if(productionEntry?.productId!=it?.id)productionEntry=null};DateField(date){date=it};Field("Bottles inspected *",inspected){inspected=it.filter(Char::isDigit).take(9)};Field("Bottles passed *",passed){passed=it.filter(Char::isDigit).take(9)};Surface(color=if(failed>0)Color(0xFFFFF3DE) else Color(0xFFE8F7F0),shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("Failed bottles",Modifier.weight(1f),fontSize=12.sp,color=Navy);Text(failed.coerceAtLeast(0).toString(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=if(failed>0)Color(0xFFB45309) else Color(0xFF16804A))}};Text("Defect category",fontSize=12.sp,color=Muted);Column{listOf("Cap / seal defect","Leakage","Bottle deformation","Low fill","Contamination","Label / print defect","Colour / clarity","Other").chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){row.forEach{t->FilterChip(selected=category==t,onClick={category=t},label={Text(t,fontSize=10.sp)})}}}};Text("Severity",fontSize=12.sp,color=Muted);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("MINOR","MAJOR","CRITICAL").forEach{t->FilterChip(selected=severity==t,onClick={severity=t},label={Text(t)})}};OutlinedTextField(notes,{notes=it},label={Text("Inspection notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=product!=null&&i>0&&p in 0..i&&date.length==10,onClick={save(product!!,productionEntry,date,i,p,failed,category,severity,notes.trim())}){Text("Save inspection")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun ProductionPlanForm(products:List<ProductEntity>,machines:List<MachineEntity>,close:()->Unit,save:(ProductEntity,String,String,Int,Long?,String)->Unit){
 var product by remember{mutableStateOf(products.firstOrNull())};var date by remember{mutableStateOf(today())};var shift by remember{mutableStateOf("DAY")};var target by remember{mutableStateOf("")};var machine by remember{mutableStateOf(machines.firstOrNull())};var notes by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Create production plan",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Set a target for a specific product, date and shift.",fontSize=12.sp,color=Muted);ProductPicker(products,product){product=it};DateField(date){date=it};Text("Shift",fontSize=12.sp,color=Muted);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("DAY","EVENING","NIGHT").forEach{t->FilterChip(selected=shift==t,onClick={shift=t},label={Text(t)})}};Field("Target bottles *",target){target=it.filter(Char::isDigit).take(9)};Text("Assigned machine (optional)",fontSize=12.sp,color=Muted);DropdownMachinePicker(machines,machine){machine=it};OutlinedTextField(notes,{notes=it},label={Text("Plan notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=product!=null&&(target.toIntOrNull()?:0)>0&&date.length==10,onClick={save(product!!,date,shift,target.toInt(),machine?.id,notes.trim())}){Text("Save plan")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun DropdownMachinePicker(machines:List<MachineEntity>,selected:MachineEntity?,onSelect:(MachineEntity?)->Unit){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(selected?.name?:"No machine assigned")};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){DropdownMenuItem(text={Text("No machine assigned")},onClick={onSelect(null).let{expanded=false}});machines.forEach{m->DropdownMenuItem(text={Text(m.name+" · "+m.status)},onClick={onSelect(m).let{expanded=false}})}}}}
@Composable private fun SalesOrderForm(customers:List<CustomerEntity>,products:List<ProductEntity>,close:()->Unit,save:(CustomerEntity,ProductEntity,String,String,String,Int,String)->Unit){
 var customer by remember{mutableStateOf(customers.firstOrNull())};var product by remember{mutableStateOf(products.firstOrNull())};var number by remember{mutableStateOf("SO-"+System.currentTimeMillis().toString().takeLast(7))};var date by remember{mutableStateOf(today())};var due by remember{mutableStateOf(today())};var packs by remember{mutableStateOf("")};var notes by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("New sales order",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Create a customer order and track fulfilment against dispatches.",fontSize=12.sp,color=Muted);CustomerPicker(customers,customer){customer=it};Field("Order number *",number){number=it.trim().take(40)};DateField(date){date=it};DateField(due){due=it};ProductPicker(products.filter{it.active&&it.bottlesPerPack>0},product){product=it};Field("Packs ordered *",packs){packs=it.filter(Char::isDigit).take(9)};OutlinedTextField(notes,{notes=it},label={Text("Order notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=customer!=null&&product!=null&&number.isNotBlank()&&(packs.toIntOrNull()?:0)>0&&product!!.bottlesPerPack>0,onClick={save(customer!!,product!!,number,date,due,packs.toInt(),notes.trim())}){Text("Create order")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun FulfilOrderForm(order:SalesOrderEntity,lines:List<SalesOrderLineEntity>,close:()->Unit,save:(SalesOrderLineEntity,Int)->Unit){
 var selected by remember{mutableStateOf(lines.firstOrNull())};var quantity by remember{mutableStateOf(selected?.packsDispatched?.toString().orEmpty())};var dispatchRef by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Update order fulfilment",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(order.orderNumber+" · "+order.customerNameSnapshot,fontWeight=FontWeight.Bold,color=Blue);Text("Choose a line and record cumulative packs fulfilled. This records fulfilment progress; it does not dispatch stock.",fontSize=12.sp,color=Muted);lines.forEach{line->FilterChip(selected=selected?.id==line.id,onClick={selected=line;quantity=line.packsDispatched.toString()},label={Text(line.productNameSnapshot+" · "+line.packsDispatched+"/"+line.packsOrdered)})};Field("Cumulative packs fulfilled",quantity){quantity=it.filter(Char::isDigit).take(9)};Field("Dispatch reference (optional)",dispatchRef){dispatchRef=it}}},confirmButton={Button(enabled=selected!=null&&(quantity.toIntOrNull()?:-1) in 0..(selected?.packsOrdered?:0),onClick={selected?.let{save(it,quantity.toInt())}}){Text("Save fulfilment")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun StockMiniMetric(title:String,value:Int,modifier:Modifier=Modifier){
 Surface(modifier,color=Page,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(10.dp)){Text(title,fontSize=10.sp,color=Muted);Text(value.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)}}
}

@Composable private fun EmptyDashboardState(title:String,detail:String,icon:androidx.compose.ui.graphics.vector.ImageVector){
 Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
  Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
   Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(16.dp)){Icon(icon,null,tint=Blue,modifier=Modifier.padding(14.dp).size(28.dp))}
   Text(title,fontWeight=FontWeight.Bold,color=Navy)
   Text(detail,color=Muted,fontSize=12.sp)
  }
 }
}

@Composable private fun Metric(title:String,value:String,modifier:Modifier=Modifier){Surface(modifier,color=Color.White,shape=RoundedCornerShape(16.dp),shadowElevation=1.dp){Column(Modifier.padding(15.dp)){Text(title,color=Blue,fontSize=12.sp);Text(value,color=Navy,fontWeight=FontWeight.Bold,fontSize=23.sp)}}}
@Composable private fun Action(title:String,icon:androidx.compose.ui.graphics.vector.ImageVector,click:()->Unit,modifier:Modifier=Modifier){Surface(onClick=click,modifier=modifier,color=Color.White,shape=RoundedCornerShape(14.dp),shadowElevation=1.dp){Column(Modifier.fillMaxWidth().padding(13.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=Blue);Text(title,fontSize=11.sp,color=Navy)}}}
@Composable private fun Record(title:String,detail:String,sub:String){Surface(color=Color.White,shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(13.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.WaterDrop,null,tint=Cyan);Column(Modifier.weight(1f).padding(start=10.dp)){Text(title,fontWeight=FontWeight.Bold,color=Navy);Text(detail,fontSize=12.sp,color=Blue);Text(sub,fontSize=10.sp,color=Color.Gray)}}}}
@Composable private fun ListScreen(title:String,rows:List<Triple<String,String,String>>,add:()->Unit){Column(Modifier.fillMaxSize().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Bold,color=Navy);Button(onClick=add){Text("+ Add")}};Spacer(Modifier.height(10.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(rows){Record(it.first,it.second,it.third)}}}}
@Composable private fun Field(label:String,value:String,onChange:(String)->Unit){Field(label,value,onChange,false)}
@Composable private fun Field(label:String,value:String,onChange:(String)->Unit,decimal:Boolean){OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(decimal)KeyboardType.Decimal else KeyboardType.Number),modifier=Modifier.fillMaxWidth())}
@Composable private fun DateField(value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={Text("Date (yyyy-MM-dd)")},singleLine=true,modifier=Modifier.fillMaxWidth())}
@Composable private fun ProductPicker(products:List<ProductEntity>,selected:ProductEntity?,onSelect:(ProductEntity?)->Unit){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(selected?.name?:"Select product")};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){products.forEach{p->DropdownMenuItem(text={Text(p.name)},onClick={onSelect(p).let{expanded=false}})}}}}
@OptIn(ExperimentalMaterial3Api::class)\n@Composable private fun ProductForm(initial:ProductEntity?,close:()->Unit,save:(String,String,String,Int)->Unit){
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var size by remember(initial?.id){mutableStateOf(initial?.sizeLabel.orEmpty())}
 var colour by remember(initial?.id){mutableStateOf(initial?.colour?:"Clear")}
 var pack by remember(initial?.id){mutableStateOf((initial?.bottlesPerPack?:0).let{if(it>0)it.toString() else ""})}
 var colourExpanded by remember{mutableStateOf(false)}
 val packCount=pack.toIntOrNull()
 val validName=name.isNotBlank()&&name.trim().length<=80
 val validSize=size.isNotBlank()&&size.trim().length<=40
 val validPack=pack.isBlank()||(packCount!=null&&packCount in 1..100000)
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text(if(initial==null)"Add product" else "Edit product",fontWeight=FontWeight.ExtraBold,color=Navy,fontSize=22.sp)
   Text(if(initial==null)"Create a bottle type and its pack configuration." else "Update configuration without changing historical transactions.",fontSize=12.sp,color=Muted)
  }},
  text={Column(verticalArrangement=Arrangement.spacedBy(11.dp)){
   Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(16.dp)){
    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
     Surface(color=Color.White,shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.WaterDrop,null,tint=Blue,modifier=Modifier.padding(12.dp).size(28.dp))}
     Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
      Text(name.trim().ifBlank{"New bottle product"},fontWeight=FontWeight.Bold,color=Navy)
      Text(size.trim().ifBlank{"Bottle size not set"}+" · "+colour, fontSize=11.sp,color=Muted)
      Text(if(packCount!=null&&packCount>0)"$packCount bottles per pack" else "Pack configuration required for packing",fontSize=11.sp,color=if(packCount!=null&&packCount>0)Color(0xFF16804A) else Color(0xFF9B5B00))
     }
    }
   }
   OutlinedTextField(value=name,onValueChange={name=it},label={Text("Product name *")},singleLine=true,isError=!validName,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Label,null)},supportingText={if(!validName)Text("Enter a name up to 80 characters.")})
   OutlinedTextField(value=size,onValueChange={size=it},label={Text("Bottle size / type *")},singleLine=true,isError=!validSize,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Straighten,null)},placeholder={Text("e.g. 500 ml")},supportingText={if(!validSize)Text("Enter the bottle size or type.")})
   ExposedDropdownMenuBox(expanded=colourExpanded,onExpandedChange={colourExpanded=it}){
    OutlinedTextField(value=colour,onValueChange={colour=it},readOnly=true,label={Text("Bottle colour")},singleLine=true,modifier=Modifier.menuAnchor().fillMaxWidth(),leadingIcon={Icon(Icons.Default.Palette,null)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=colourExpanded)})
    ExposedDropdownMenu(expanded=colourExpanded,onDismissRequest={colourExpanded=false}){listOf("Clear","Green","Milk White","Blue","Amber","Other").forEach{option->DropdownMenuItem(text={Text(option)},onClick={colour=option;colourExpanded=false})}}
   }
   OutlinedTextField(value=pack,onValueChange={pack=it.filter(Char::isDigit).take(6)},label={Text("Bottles per pack")},singleLine=true,isError=!validPack,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Inventory2,null)},placeholder={Text("e.g. 24")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),supportingText={Text(if(pack.isBlank())"Optional during setup; packing stays blocked until configured." else if(!validPack)"Enter a quantity from 1 to 100000." else "Used to calculate complete packs and loose bottles.")})
   if(initial!=null)Text("Product ID: "+initial.id+" · Existing history is retained.",fontSize=10.sp,color=Muted)
  }},
  confirmButton={Button(enabled=validName&&validSize&&validPack,onClick={save(name.trim(),size.trim(),colour.trim(),packCount?:0)},shape=RoundedCornerShape(12.dp)){Text(if(initial==null)"Save product" else "Save changes")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )}
@Composable private fun ProductionForm(products:List<ProductEntity>,close:()->Unit,save:(ProductEntity?,String,Int,Int,Int,String,String)->Unit){
 var p by remember{mutableStateOf(products.firstOrNull())}
 var d by remember{mutableStateOf(today())}
 var total by remember{mutableStateOf("")}
 var good by remember{mutableStateOf("")}
 var rejected by remember{mutableStateOf("0")}
 var machine by remember{mutableStateOf("Machine 1")}
 var remarks by remember{mutableStateOf("")}
 var shift by remember{mutableStateOf("Day")}
 var rejectReason by remember{mutableStateOf("")}
 val t=total.toIntOrNull();val g=good.toIntOrNull();val r=rejected.toIntOrNull()
 val quantitiesValid=t!=null&&g!=null&&r!=null&&t>0&&g>=0&&r>=0&&g+r==t
 val dateValid=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(d)!=null}.getOrDefault(false)&&d.length==10
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text("Add production entry",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Record output for one product and production run.",fontSize=12.sp,color=Muted)}},
  text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Build,null,tint=Blue,modifier=Modifier.padding(10.dp))}
    Column{Text("Step 1 of 3 · Production details",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp);Text("Product, date, shift and machine",fontSize=11.sp,color=Muted)}
   }
   ProductPicker(products,p){p=it}
   p?.let{Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(Icons.Default.WaterDrop,null,tint=Blue);Column{Text(it.name,fontWeight=FontWeight.Bold,color=Navy);Text(it.sizeLabel+" · "+it.colour,fontSize=11.sp,color=Muted)}}}}
   DateField(d){d=it}
   if(!dateValid)Text("Use a valid date in YYYY-MM-DD format.",fontSize=11.sp,color=Color(0xFFB42318))
   Text("Shift",fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    listOf("Day","Evening","Night").forEach{value->FilterChip(selected=shift==value,onClick={shift=value},label={Text(value)},leadingIcon=if(shift==value)({Icon(Icons.Default.Check,null,modifier=Modifier.size(16.dp))}) else null)}
   }
   OutlinedTextField(machine,{machine=it},label={Text("Machine / line *")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Settings,null)})
   HorizontalDivider(color=Border)
   Text("Step 2 · Output quantities",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
   OutlinedTextField(total,{total=it.filter(Char::isDigit).take(9)},label={Text("Produced bottles *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Build,null)})
   OutlinedTextField(good,{good=it.filter(Char::isDigit).take(9)},label={Text("Good bottles *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.CheckCircle,null)})
   OutlinedTextField(rejected,{rejected=it.filter(Char::isDigit).take(9)},label={Text("Rejected bottles *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.WarningAmber,null)})
   if(t!=null&&g!=null&&r!=null){
    val valid=t>0&&g>=0&&r>=0&&g+r==t
    Surface(color=if(valid)Color(0xFFE8F7F0) else Color(0xFFFFF0F0),shape=RoundedCornerShape(12.dp)){
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Icon(if(valid)Icons.Default.CheckCircle else Icons.Default.Error,null,tint=if(valid)Color(0xFF16804A) else Color(0xFFB42318));Text(if(valid)"Output totals verified" else "Output totals do not match",fontWeight=FontWeight.Bold,color=if(valid)Color(0xFF16804A) else Color(0xFFB42318),fontSize=12.sp)}
      Text("Produced $t = Good $g + Rejected $r",fontSize=11.sp,color=Navy)
     }
    }
   }
   OutlinedTextField(rejectReason,{rejectReason=it},label={Text("Reject reason (if applicable)")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(remarks,{remarks=it},label={Text("Remarks (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
  }},
  confirmButton={Button(enabled=p!=null&&quantitiesValid&&dateValid&&machine.isNotBlank(),onClick={save(p,d,t!!,g!!,r!!,machine.trim(),listOfNotNull(if(shift.isNotBlank())"Shift: $shift" else null,if(rejectReason.isNotBlank())"Reject reason: $rejectReason" else null,remarks.trim().ifBlank{null}).joinToString(" · "))},shape=RoundedCornerShape(12.dp)){Text("Review & save")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}
@Composable private fun PackingForm(products:List<ProductEntity>,dao:FactoryDao,close:()->Unit,save:(ProductEntity?,String,Int)->Unit){
 var p by remember{mutableStateOf(products.firstOrNull())}
 var d by remember{mutableStateOf(today())}
 var bottles by remember{mutableStateOf("")}
 var available by remember{mutableIntStateOf(0)}
 val n=bottles.toIntOrNull()?:0
 val calc=runCatching{PackCalculation.calculate(n,p?.bottlesPerPack?:0)}.getOrNull()
 val dateValid=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(d)!=null}.getOrDefault(false)&&d.length==10
 LaunchedEffect(p?.id){p?.let{available=(dao.totalGoodBottles(it.id)-dao.totalPackedBottles(it.id)).coerceAtLeast(0)}}
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text("Add packing batch",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Convert available good bottles into finished packs.",fontSize=12.sp,color=Muted)}},
  text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(color=Color(0xFFF0EBFF),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Inventory2,null,tint=Color(0xFF7656D9),modifier=Modifier.padding(10.dp))}
    Column{Text("Packing details",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp);Text("Product · Date · Quantity",fontSize=11.sp,color=Muted)}
   }
   ProductPicker(products,p){p=it;bottles=""}
   p?.let{Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.WaterDrop,null,tint=Blue,modifier=Modifier.size(24.dp));Column(Modifier.weight(1f)){Text(it.name,fontWeight=FontWeight.Bold,color=Navy);Text(if(it.bottlesPerPack>0)"Pack size: "+it.bottlesPerPack+" bottles" else "Pack size not configured",fontSize=11.sp,color=Muted)};Column(horizontalAlignment=Alignment.End){Text(available.toString(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("available bottles",fontSize=9.sp,color=Muted)}}}}
   DateField(d){d=it}
   if(!dateValid)Text("Enter a valid date in YYYY-MM-DD format.",fontSize=11.sp,color=Color(0xFFB42318))
   OutlinedTextField(bottles,{bottles=it.filter(Char::isDigit).take(9)},label={Text("Good bottles to pack *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Inventory2,null)})
   if(n>available)Text("Quantity exceeds available unpacked bottles.",fontSize=11.sp,color=Color(0xFFB42318))
   if(p?.bottlesPerPack?.let{it<=0}==true)Text("Configure bottles per pack in Products before packing.",fontSize=11.sp,color=Color(0xFF9B5B00))
   if(n>0&&calc!=null&&p?.bottlesPerPack?.let{it>0}==true){
    Surface(color=Color(0xFFF0EBFF),shape=RoundedCornerShape(14.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Text("Packing calculation",fontWeight=FontWeight.Bold,color=Navy)
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
       Column(Modifier.weight(1f)){Text("Complete packs",fontSize=10.sp,color=Muted);Text(calc.completePacks.toString(),fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFF7656D9))}
       Column(Modifier.weight(1f)){Text("Loose bottles",fontSize=10.sp,color=Muted);Text(calc.looseBottles.toString(),fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=Navy)}
      }
      Text("$n bottles ÷ "+p!!.bottlesPerPack+" bottles per pack",fontSize=11.sp,color=Muted)
     }
    }
   }
   PackCalculationCard(eligibleBottles=n,bottlesPerPack=p?.bottlesPerPack?.takeIf{it>0})
  }},
  confirmButton={Button(enabled=n>0&&n<=available&&p!=null&&p?.bottlesPerPack?.let{it>0}==true&&dateValid,onClick={save(p,d,n)},shape=RoundedCornerShape(12.dp)){Text("Save packing batch")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}
@Composable private fun DispatchForm(products:List<ProductEntity>,dao:FactoryDao,close:()->Unit,save:(ProductEntity?,String,String,Int,String,String)->Unit){var p by remember{mutableStateOf(products.firstOrNull())};var d by remember{mutableStateOf(today())};var customer by remember{mutableStateOf("Nachiyar Cooldrinks")};var packs by remember{mutableStateOf("")};var ref by remember{mutableStateOf("")};var remarks by remember{mutableStateOf("")};var stock by remember{mutableIntStateOf(0)};LaunchedEffect(p?.id){p?.let{stock=dao.totalCompletePacks(it.id)-dao.totalDispatchedPacks(it.id)}};val n=packs.toIntOrNull()?:0;AlertDialog(onDismissRequest=close,title={Text("Delivery / dispatch")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){DateField(d){d=it};OutlinedTextField(customer,{customer=it},label={Text("Customer")});ProductPicker(products,p){p=it};Text("Available finished packs: "+stock.coerceAtLeast(0),color=Blue);Field("Packs to dispatch",packs){packs=it};OutlinedTextField(ref,{ref=it},label={Text("Delivery reference")});OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")})}},confirmButton={Button(enabled=n>0&&n<=stock&&p!=null&&customer.isNotBlank(),onClick={save(p,d,customer,n,ref,remarks)}){Text("Confirm dispatch")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun MeterForm(close:()->Unit,save:(String,String,Double,Double,String)->Unit){var d by remember{mutableStateOf(today())};var meter by remember{mutableStateOf("MAIN")};var start by remember{mutableStateOf("")};var end by remember{mutableStateOf("")};var remarks by remember{mutableStateOf("")};val used=runCatching{ElectricityRules.consumption(start.toDouble(),end.toDouble())}.getOrNull();AlertDialog(onDismissRequest=close,title={Text("Electricity reading")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){DateField(d){d=it};OutlinedTextField(meter,{meter=it},label={Text("Meter ID")});Field("Start reading",start,{start=it},true);Field("End reading",end,{end=it},true);Text("Consumption: "+(used?.toString()?: "—")+" units",color=Blue);OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")})}},confirmButton={Button(enabled=used!=null,onClick={if(used!=null)save(d,meter,start.toDouble(),end.toDouble(),remarks)}){Text("Save reading")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun CustomerForm(initial:CustomerEntity?,close:()->Unit,save:(String,String,String,String)->Unit){
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var phone by remember(initial?.id){mutableStateOf(initial?.phone.orEmpty())}
 var address by remember(initial?.id){mutableStateOf(initial?.address.orEmpty())}
 var gst by remember(initial?.id){mutableStateOf(initial?.gstNumber.orEmpty())}
 AlertDialog(onDismissRequest=close,title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(initial==null)"Add customer" else "Edit customer",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(if(initial==null)"Save customer contact details for dispatch." else "Update contact details. Existing dispatch snapshots remain unchanged.",fontSize=12.sp,color=Muted)}},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
  Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(14.dp)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(color=Color.White,shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.People,null,tint=Blue,modifier=Modifier.padding(10.dp))};Column{Text(name.trim().ifBlank{"Customer preview"},fontWeight=FontWeight.Bold,color=Navy);Text(phone.ifBlank{"Phone not provided"},fontSize=11.sp,color=Muted)}}}
  OutlinedTextField(name,{name=it},label={Text("Customer name *")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Person,null)})
  OutlinedTextField(phone,{phone=it},label={Text("Phone number")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Phone,null)})
  OutlinedTextField(address,{address=it},label={Text("Address")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3,leadingIcon={Icon(Icons.Default.LocationOn,null)})
  OutlinedTextField(gst,{gst=it.uppercase().filter{ch->ch.isLetterOrDigit()}.take(15)},label={Text("GSTIN (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.ReceiptLong,null)})
 }},confirmButton={Button(enabled=name.isNotBlank()&&name.trim().length<=120,onClick={save(name.trim(),phone.trim(),address.trim(),gst.trim())},shape=RoundedCornerShape(12.dp)){Text(if(initial==null)"Save customer" else "Save changes")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun CustomerPicker(customers:List<CustomerEntity>,selected:CustomerEntity?,onSelect:(CustomerEntity?)->Unit){
 var expanded by remember{mutableStateOf(false)}
 Box { OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(selected?.name?:"Select customer")}
  DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){customers.forEach{customer->DropdownMenuItem(text={Text(customer.name)},onClick={onSelect(customer).let{expanded=false}})}}
 }
}
@Composable private fun DispatchBatchForm(products:List<ProductEntity>,customers:List<CustomerEntity>,orders:List<SalesOrderEntity>,initialOrderId:Long?,close:()->Unit,save:(CustomerEntity?,String,String,String,String,List<DispatchRequestLine>,String,Long?)->Unit){
 var order by remember{mutableStateOf(orders.firstOrNull{it.id==initialOrderId})}
 var customer by remember{mutableStateOf(customers.firstOrNull{it.id==orders.firstOrNull{o->o.id==initialOrderId}?.customerId}?:customers.firstOrNull())}
 var date by remember{mutableStateOf(today())}
 var reference by remember{mutableStateOf("")}
 var remarks by remember{mutableStateOf("")}
 var lines by remember{mutableStateOf(listOf(products.firstOrNull() to ""))}
 var showReview by remember{mutableStateOf(false)}
 val key by remember{mutableStateOf(UUID.randomUUID().toString())}
 val parsed=lines.map{it.first to (it.second.toIntOrNull()?:0)}
 val valid=customer!=null&&products.isNotEmpty()&&parsed.isNotEmpty()&&parsed.all{it.first!=null&&it.second>0}&&parsed.mapNotNull{it.first?.id}.distinct().size==parsed.size
 val dateValid=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(date)!=null}.getOrDefault(false)&&date.length==10
 val totalPacks=parsed.sumOf{it.second}
 val totalBottles=parsed.sumOf{(line,qty)->qty.toLong()*(line?.bottlesPerPack?:0)}
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(showReview)"Review dispatch" else "New dispatch",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(if(showReview)"Confirm customer and quantities before posting." else "Prepare a delivery with one or more products.",fontSize=12.sp,color=Muted)}},
  text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(color=Color(0xFFFFF0E0),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.LocalShipping,null,tint=Color(0xFFD98523),modifier=Modifier.padding(10.dp))}
    Column{Text(if(showReview)"Final check" else "Dispatch details",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp);Text("Customer · Date · Products",fontSize=11.sp,color=Muted)}
   }
   if(!showReview){
    if(orders.isNotEmpty()){
     Text("LINK TO SALES ORDER",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue)
     orders.forEach{o->FilterChip(selected=order?.id==o.id,onClick={order=o;customer=customers.firstOrNull{it.id==o.customerId};lines=listOf(products.firstOrNull() to "")},label={Text(o.orderNumber+" · "+o.customerNameSnapshot+" · due "+o.dueDate,fontSize=10.sp)})}
     TextButton(onClick={order=null}){Text("Dispatch without an order")}
    }
    CustomerPicker(customers,customer){selected->if(order!=null&&selected?.id!=order?.customerId)order=null;customer=selected}
    if(customers.isEmpty())Text("Add a customer first in Customers.",color=Color(0xFFB42318),fontSize=11.sp)
    DateField(date){date=it}
    if(!dateValid)Text("Enter a valid date in YYYY-MM-DD format.",fontSize=11.sp,color=Color(0xFFB42318))
    OutlinedTextField(reference,{reference=it},label={Text("Dispatch reference (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.ReceiptLong,null)})
    Text("Dispatch items",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
    lines.forEachIndexed{index,line->
     Surface(color=Page,shape=RoundedCornerShape(14.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
      Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
       Row(verticalAlignment=Alignment.CenterVertically){Text("Item "+(index+1),Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Navy,fontSize=12.sp);if(lines.size>1)TextButton(onClick={lines=lines.filterIndexed{idx,_->idx!=index}}){Text("Remove",color=Color(0xFFB42318))}}
       ProductPicker(products,line.first){p->lines=lines.toMutableList().also{it[index]=p to line.second}}
       Field("Number of packs",line.second){qty->lines=lines.toMutableList().also{it[index]=line.first to qty.filter(Char::isDigit).take(9)}}
       val qty=line.second.toIntOrNull()?:0
       if(line.first!=null&&qty>0){
        val packSize=line.first!!.bottlesPerPack
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
         Text(if(packSize>0)"Estimated bottles" else "Pack size not configured",fontSize=10.sp,color=Muted)
         if(packSize>0)Text((qty.toLong()*packSize).toString(),fontWeight=FontWeight.Bold,color=Navy,fontSize=12.sp)
        }
       }
       if(line.first?.bottlesPerPack?.let{it<=0}==true)Text("Set this product's pack size before dispatch.",fontSize=11.sp,color=Color(0xFF9B5B00))
      }
     }
    }
    TextButton(onClick={lines=lines+(products.firstOrNull() to "")},enabled=products.isNotEmpty()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("Add another product")}
    OutlinedTextField(remarks,{remarks=it},label={Text("Delivery notes (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
   }else{
    Surface(color=Color(0xFFFFF0E0),shape=RoundedCornerShape(14.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text("CUSTOMER",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=1.sp)
      Text(customer?.name.orEmpty(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)
      Text("Dispatch date  ·  "+date,fontSize=12.sp,color=Muted)
      HorizontalDivider(color=Border)
      Text("ORDER SUMMARY",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=1.sp)
      parsed.forEach{(product,qty)->if(product!=null)Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(product.name,fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp);Text(if(product.bottlesPerPack>0)product.bottlesPerPack.toString()+" bottles per pack" else "Pack size not configured",fontSize=10.sp,color=Muted)};Text(qty.toString()+" packs",fontWeight=FontWeight.Bold,color=Navy)}}
      HorizontalDivider(color=Border)
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Total packs",fontSize=11.sp,color=Muted);Text(totalPacks.toString(),fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)};Column(horizontalAlignment=Alignment.End){Text("Estimated bottles",fontSize=11.sp,color=Muted);Text(totalBottles.toString(),fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFFD98523))}}
      Text("Posting checks current stock and permissions. The dispatch is not saved until you confirm.",fontSize=11.sp,color=Muted)
     }
    }
   }
  }},
  confirmButton={if(showReview)Button(enabled=valid&&dateValid,onClick={save(customer,customer?.name.orEmpty(),date,reference,remarks,parsed.map{DispatchRequestLine(it.first!!.id,it.second)},key,order?.id)},shape=RoundedCornerShape(12.dp)){Text("Confirm & post")}else Button(enabled=valid&&dateValid&&parsed.all{it.first!!.bottlesPerPack>0},onClick={showReview=true},shape=RoundedCornerShape(12.dp)){Text("Review dispatch")}},
  dismissButton={TextButton(onClick={if(showReview)showReview=false else close()}){Text(if(showReview)"Back to edit" else "Cancel")}}
 )
}
@Composable private fun LoginForm(close:()->Unit,signIn:(String,String)->Unit){
 var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Factory staff sign-in")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Firebase must be configured for cloud accounts. Role and organisation are read from trusted Firebase custom claims.",fontSize=12.sp,color=Blue);OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}},confirmButton={Button(enabled=email.isNotBlank()&&password.isNotBlank(),onClick={signIn(email.trim(),password)}){Text("Sign in")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable private fun ReverseDispatchForm(close:()->Unit,reverse:(String)->Unit){
 var reason by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Reverse dispatch")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("This records a compensating inventory movement and keeps the original dispatch in the audit history.",fontSize=12.sp,color=Blue);OutlinedTextField(reason,{reason=it},label={Text("Reason for reversal")},modifier=Modifier.fillMaxWidth())}},confirmButton={Button(enabled=reason.isNotBlank(),onClick={reverse(reason.trim())}){Text("Confirm reversal")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable private fun ReversePackingForm(close:()->Unit,reverse:(String)->Unit){var reason by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text("Reverse packing entry")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Only reverse when the packed stock has not already been dispatched. This creates compensating ledger movements.",fontSize=12.sp,color=Blue);OutlinedTextField(reason,{reason=it},label={Text("Reason for reversal")},modifier=Modifier.fillMaxWidth())}},confirmButton={Button(enabled=reason.isNotBlank(),onClick={reverse(reason.trim())}){Text("Confirm reversal")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
