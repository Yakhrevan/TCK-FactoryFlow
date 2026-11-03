package com.tck.factoryflow
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.tck.factoryflow.sync.CloudInventoryRepository
import com.tck.factoryflow.sync.CloudStockSnapshot
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import com.tck.factoryflow.ui.components.BottleProductCard
import com.tck.factoryflow.ui.components.ProductThumbnail
import com.tck.factoryflow.ui.components.BottleIconPicker
import com.tck.factoryflow.ui.components.PackCalculationCard
import com.tck.factoryflow.ui.components.StartupErrorScreen
import com.tck.factoryflow.ui.components.StartupLoginScreen
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private val Blue=Color(0xFF0756A4); private val Cyan=Color(0xFF13A7D8); private val Navy=Color(0xFF102B4E); private val Page=Color(0xFFF1F7FC); private val Muted=Color(0xFF6B7E93); private val Border=Color(0xFFDCE7F1)
private fun today()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
private fun isServerOnline(context: android.content.Context): Boolean {
 val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
 val net = cm.activeNetwork ?: return false
 val caps = cm.getNetworkCapabilities(net) ?: return false
 return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
private fun requireCloudSession(session: FactorySession?): FactorySession {
 val cloud = session ?: error("Please sign in to your cloud account.")
 check(cloud.isCloud) { "Cloud-only operation. Please sign in with your provisioned account." }
 check(cloud.uid.isNotBlank()) { "Authenticated user ID is missing. Please sign in again." }
 check(cloud.organisationId.isNotBlank()) { "Organisation is missing. Contact your administrator." }
 check(cloud.factoryIds.any { it.isNotBlank() }) { "No factory is assigned to your account." }
 return cloud
}

private fun roleAllows(session:FactorySession?,action:FactoryAction):Boolean {
 if(session==null) return false
 val roleCheck = runCatching{FactoryPermissions.requireAllowed(FactoryPermissions.fromClaim(session.role),action)}.isSuccess
 if(!roleCheck) return false
 if(session.role.equals("admin",true) || session.role.equals("owner",true)) return true
 val rawModules = session.allowedModules.trim()
 if(rawModules.isBlank() || rawModules.equals("ALL",ignoreCase=true)) return true
 val mods = rawModules.split(",").map{it.trim().uppercase()}.filter{it.isNotEmpty()}
 return when(action) {
  FactoryAction.CREATE_PRODUCTION, FactoryAction.CORRECT_RECORDS -> "PRODUCTION" in mods
  FactoryAction.CREATE_PACKING -> "PACKING" in mods
  FactoryAction.POST_DISPATCH, FactoryAction.REVERSE_DISPATCH, FactoryAction.MANAGE_ORDERS -> "DISPATCH" in mods
  FactoryAction.MANAGE_QUALITY -> "QUALITY" in mods
  FactoryAction.MANAGE_MACHINES -> "MACHINES" in mods
  FactoryAction.MANAGE_PRODUCTS, FactoryAction.ADJUST_STOCK -> "PRODUCTS" in mods
  FactoryAction.EXPORT_REPORTS -> "REPORTS" in mods
  FactoryAction.MANAGE_STAFF -> "STAFF" in mods
  FactoryAction.MANAGE_ELECTRICITY -> "ELECTRICITY" in mods
  FactoryAction.MANAGE_CUSTOMERS -> "CUSTOMERS" in mods
  FactoryAction.VIEW -> true
 }
}
class MainActivity:ComponentActivity(){
 private val db by lazy{FactoryDatabase.get(this)}
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val daoResult = runCatching { db.factoryDao() }
  if (daoResult.isFailure) {
   val err = daoResult.exceptionOrNull()?.message ?: "Database initialization error"
   setContent { StartupErrorScreen(error = err, onRetry = { recreate() }) }
   return
  }
  val dao = daoResult.getOrThrow()
  lifecycleScope.launch {
   runCatching {
    if (dao.activeProducts().isEmpty()) {
     listOf(
      ProductEntity(name="300ml",sizeLabel="300ml",bottlesPerPack=24),
      ProductEntity(name="500ml",sizeLabel="500ml",bottlesPerPack=24),
      ProductEntity(name="1 Ltr",sizeLabel="1 litre",bottlesPerPack=12),
      ProductEntity(name="200ml Green",sizeLabel="200ml",colour="Green",bottlesPerPack=30),
      ProductEntity(name="200ml Milk White",sizeLabel="200ml",colour="Milk White",bottlesPerPack=30),
      ProductEntity(name="200ml Clear",sizeLabel="200ml",colour="Clear",bottlesPerPack=30)
     ).forEach { dao.insertProduct(it) }
    } else {
     dao.activeProducts().filter { it.bottlesPerPack <= 0 }.forEach {
      val fallbackPack = if (it.sizeLabel.contains("1", true) || it.name.contains("1 Ltr", true)) 12 else 24
      dao.updateProduct(it.id, it.name, it.sizeLabel, it.colour, fallbackPack, it.active)
     }
    }
    if (dao.activeProductionLines().isEmpty()) {
     val line1Id = dao.insertProductionLine(
      ProductionLineEntity(
       lineCode = "LINE-01",
       name = "Production Line 1",
       description = "Main blow-moulding line",
       targetCapacity = 2500,
       capacityUnit = "BOTTLES_PER_HOUR",
       status = "ACTIVE"
      )
     )
     if (dao.activeMachines().isEmpty()) {
      dao.insertMachine(
       MachineEntity(machineCode="BLOW-01",name="Blow Machine 1",machineType="Blowing",location="Line 01 - Bay A",status="RUNNING",lineId=line1Id)
      )
     }
    }
   }
  }
  if (FirebaseApp.getApps(this).isNotEmpty()) {
   SyncScheduler.schedule(this)
   SyncScheduler.runNow(this)
  }
  setContent { FactoryApp(dao) }
 }
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

private enum class PageTab{HOME,PRODUCTS,PRODUCTION,PACKING,STOCK,DISPATCH,ELECTRICITY,REPORTS,CUSTOMERS,MACHINES,QUALITY,PLANNING,ORDERS,MOVEMENTS,AUDIT,STAFF,SYNC}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FactoryApp(dao:FactoryDao){
  val backStack = remember { androidx.compose.runtime.mutableStateListOf(PageTab.HOME) }
  var tab by remember { mutableStateOf(PageTab.HOME) }
  val setTab: (PageTab) -> Unit = { newTab ->
   if (newTab != tab) {
    if (newTab == PageTab.HOME) {
     backStack.clear()
     backStack.add(PageTab.HOME)
    } else {
     backStack.remove(newTab)
     backStack.add(newTab)
    }
    tab = newTab
   }
  }
  var showTools by remember{mutableStateOf(false)}; var dialog by remember{mutableStateOf("")}; var session by remember{mutableStateOf<FactorySession?>(null)}; var isCheckingSession by remember{mutableStateOf(true)}; var editingProduct by remember{mutableStateOf<ProductEntity?>(null)}; var deletingProduct by remember{mutableStateOf<ProductEntity?>(null)}; var deletingMachine by remember{mutableStateOf<MachineEntity?>(null)}; var deletingLine by remember{mutableStateOf<ProductionLineEntity?>(null)}; var editingStaff by remember{mutableStateOf<StaffMemberEntity?>(null)}; var productSubTab by remember{mutableIntStateOf(0)}; var editingCustomer by remember{mutableStateOf<CustomerEntity?>(null)}; var selectedMachineId by remember{mutableStateOf<Long?>(null)}; var selectedMachineEventId by remember{mutableStateOf<Long?>(null)}; var selectedOrderId by remember{mutableStateOf<Long?>(null)};var editingPlan by remember{mutableStateOf<ProductionPlanEntity?>(null)};var editingOrder by remember{mutableStateOf<SalesOrderEntity?>(null)}; var reversalBatchId by remember{mutableStateOf<Long?>(null)}; var reversalPackingId by remember{mutableStateOf<Long?>(null)}; var reversalProductionId by remember{mutableStateOf<Long?>(null)};var stockAdjustmentMode by remember{mutableStateOf("ADD")}; var stockAdjustmentUnit by remember{mutableStateOf("PACK")}; var stockAdjustmentProduct by remember{mutableStateOf<ProductEntity?>(null)}; var stockAdjustmentQuantity by remember{mutableStateOf("1")}; var stockAdjustmentReason by remember{mutableStateOf("")}; var message by remember{mutableStateOf<String?>(null)}; var reportFrom by remember{mutableStateOf(today().substring(0,7)+"-01")}; var reportTo by remember{mutableStateOf(today())}; var productSearch by remember{mutableStateOf("")}; var movementSearch by remember{mutableStateOf("")}; var stockSearch by remember{mutableStateOf("")}; var productionSearch by remember{mutableStateOf("")}; var customerSearch by remember{mutableStateOf("")}; var dispatchSearch by remember{mutableStateOf("")}; var editingLine by remember{mutableStateOf<ProductionLineEntity?>(null)}; var editingMachine by remember{mutableStateOf<MachineEntity?>(null)}; var assigningMachine by remember{mutableStateOf<MachineEntity?>(null)}; var machineSubTab by remember{mutableIntStateOf(0)}; var selectedLineFilterId by remember{mutableStateOf<Long?>(null)}; var machineSearch by remember{mutableStateOf("")}; val scope=rememberCoroutineScope(); var cloudStockSnapshot by remember{mutableStateOf<CloudStockSnapshot?>(null)}; var cloudStockError by remember{mutableStateOf<String?>(null)}; var cloudStockLoading by remember{mutableStateOf(false)}; var meterDialogMode by remember{mutableStateOf("MORNING")}; var selectedMeterReading by remember{mutableStateOf<MeterReadingEntity?>(null)}
  val products by dao.observeProducts().collectAsStateWithLifecycle(emptyList()); val staffMembers by dao.observeStaffMembers().collectAsStateWithLifecycle(emptyList()); val productionLines by dao.observeProductionLines().collectAsStateWithLifecycle(emptyList()); val production by dao.observeProduction().collectAsStateWithLifecycle(emptyList()); val packing by dao.observePacking().collectAsStateWithLifecycle(emptyList()); val dispatches by dao.observeDispatches().collectAsStateWithLifecycle(emptyList()); val dispatchBatches by dao.observeDispatchBatches().collectAsStateWithLifecycle(emptyList()); val dispatchLines by dao.observeDispatchLines().collectAsStateWithLifecycle(emptyList()); val meters by dao.observeMeterReadings().collectAsStateWithLifecycle(emptyList()); val customers by dao.observeCustomers().collectAsStateWithLifecycle(emptyList())
val archivedCustomers by dao.observeArchivedCustomers().collectAsStateWithLifecycle(emptyList()); val machines by dao.observeMachines().collectAsStateWithLifecycle(emptyList()); val machineEvents by dao.observeMachineEvents().collectAsStateWithLifecycle(emptyList()); val qualityInspections by dao.observeQualityInspections().collectAsStateWithLifecycle(emptyList()); val plans by dao.observeProductionPlans().collectAsStateWithLifecycle(emptyList()); val orders by dao.observeSalesOrders().collectAsStateWithLifecycle(emptyList()); val openOrders by dao.observeOpenSalesOrders().collectAsStateWithLifecycle(emptyList()); val orderLines by dao.observeSalesOrderLines().collectAsStateWithLifecycle(emptyList()); val inventoryMovements by dao.observeRecentInventoryMovements().collectAsStateWithLifecycle(emptyList()); val auditEvents by dao.observeAuditEvents().collectAsStateWithLifecycle(emptyList()); val syncEvents by dao.observeSyncOutbox().collectAsStateWithLifecycle(emptyList()); val active=products.filter{it.active}; val date=today()
  val context=LocalContext.current
  val canGoBack = dialog.isNotBlank() || showTools || backStack.size > 1
  androidx.activity.compose.BackHandler(enabled = canGoBack) {
   when {
    dialog.isNotBlank() -> dialog = ""
    showTools -> showTools = false
    backStack.size > 1 -> {
     backStack.removeAt(backStack.lastIndex)
     tab = backStack.lastOrNull() ?: PageTab.HOME
    }
   }
  }
  LaunchedEffect(context){
   session=runCatching{FactoryAuthRepository(context).currentSession()}.getOrNull()
   isCheckingSession=false
  }
  LaunchedEffect(session?.uid,tab){
   if(tab in setOf(PageTab.HOME,PageTab.STOCK,PageTab.PACKING,PageTab.PRODUCTION)&&session?.isCloud==true){
    cloudStockLoading=true
    cloudStockError=null
    runCatching{CloudInventoryRepository(context).fetchStock(session!!)}.onSuccess{snapshot->
     cloudStockSnapshot=snapshot
     snapshot.items.forEach{item->
      val byCode=dao.productByCode(item.productId)
      if(byCode!=null){
       if(byCode.name!=item.name||byCode.sizeLabel!=item.sizeLabel||byCode.colour!=item.colour||byCode.bottlesPerPack!=item.bottlesPerPack||!byCode.active) runCatching{dao.updateProductDetails(byCode.id,item.name,item.sizeLabel,item.colour,item.bottlesPerPack,item.active,byCode.iconName,byCode.imageUri)}
      }else{
       val byName=dao.productByName(item.name)
       if(byName==null){
        runCatching{dao.insertProduct(ProductEntity(name=item.name,sizeLabel=item.sizeLabel,colour=item.colour,bottlesPerPack=item.bottlesPerPack,active=item.active,productCode=item.productId))}
       }else{
        if(byName.productCode!=item.productId)dao.updateProductCode(byName.id,item.productId)
        if(byName.name!=item.name||byName.sizeLabel!=item.sizeLabel||byName.colour!=item.colour||byName.bottlesPerPack!=item.bottlesPerPack||byName.active!=item.active)dao.updateProductDetails(byName.id,item.name,item.sizeLabel,item.colour,item.bottlesPerPack,item.active,byName.iconName,byName.imageUri)
       }
      }
     }
     val knownCodes=snapshot.items.map{it.productId}.toSet()
     val knownNames=snapshot.items.map{it.name.trim().lowercase()}.toSet()
     val catalogRepo=CloudInventoryRepository(context)
     products.filter{it.productCode !in knownCodes && it.name.trim().lowercase() !in knownNames}.forEach{catalogRepo.upsertProduct(session!!,it)}
     if(products.any{it.productCode !in knownCodes && it.name.trim().lowercase() !in knownNames}) cloudStockSnapshot=catalogRepo.fetchStock(session!!)
    }.onFailure{cloudStockError=it.message?:"Unable to load authoritative cloud stock"}
    cloudStockLoading=false
   }else if(session?.isCloud!=true){cloudStockSnapshot=null;cloudStockError=null}
  }
 LaunchedEffect(message){if(message!=null){kotlinx.coroutines.delay(2600);message=null}}
 val databaseBackupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
  if(uri!=null) runCatching { FactoryBackupManager.exportSnapshot(context,uri) }.onSuccess { message="Validated database snapshot exported" }.onFailure { message="Database backup failed: "+it.message }
 }
 val databaseRestoreLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri!=null) scope.launch { runCatching { require(roleAllows(session,FactoryAction.MANAGE_STAFF)){"Only an owner or administrator can restore a database backup"};FactoryBackupManager.restoreSnapshot(context,uri) }.onSuccess { message="Database restored and integrity checked";(context as? ComponentActivity)?.recreate() }.onFailure { message="Restore rejected: "+it.message;(context as? ComponentActivity)?.recreate() } }
 }
 val backupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
  if(uri!=null) runCatching {
   val root=JSONObject().put("format","tck-factoryflow-backup").put("version",2).put("exportedAt",System.currentTimeMillis())
   root.put("products",JSONArray().apply{products.forEach{put(JSONObject().put("id",it.id).put("name",it.name).put("sizeLabel",it.sizeLabel).put("colour",it.colour).put("bottlesPerPack",it.bottlesPerPack).put("active",it.active).put("createdAt",it.createdAt))}})
   root.put("productionLines",JSONArray().apply{productionLines.forEach{put(JSONObject().put("id",it.id).put("code",it.lineCode).put("name",it.name).put("description",it.description).put("status",it.status).put("targetCapacity",it.targetCapacity).put("capacityUnit",it.capacityUnit))}})
   root.put("production",JSONArray().apply{production.forEach{put(JSONObject().put("id",it.id).put("productId",it.productId).put("productName",it.productNameSnapshot).put("date",it.productionDate).put("produced",it.producedBottles).put("good",it.goodBottles).put("rejected",it.rejectedBottles).put("machine",it.machineName).put("lineId",it.lineId).put("lineNameSnapshot",it.lineNameSnapshot).put("machineId",it.machineId).put("remarks",it.remarks).put("createdAt",it.createdAt))}})
   root.put("packing",JSONArray().apply{packing.forEach{put(JSONObject().put("id",it.id).put("productId",it.productId).put("productName",it.productNameSnapshot).put("date",it.packingDate).put("bottlesConsumed",it.bottlesConsumed).put("completePacks",it.completePacks).put("looseBottles",it.looseBottles).put("createdAt",it.createdAt))}})
   root.put("dispatchBatches",JSONArray().apply{dispatchBatches.forEach{put(JSONObject().put("id",it.id).put("reference",it.dispatchReference).put("date",it.dispatchDate).put("customer",it.customerNameSnapshot).put("status",it.status).put("remarks",it.remarks).put("createdAt",it.createdAt))}})
   root.put("dispatchLines",JSONArray().apply{dispatchLines.forEach{put(JSONObject().put("id",it.id).put("batchId",it.batchId).put("productId",it.productId).put("productName",it.productNameSnapshot).put("packs",it.packs).put("bottles",it.bottles))}})
   root.put("customers",JSONArray().apply{customers.forEach{put(JSONObject().put("id",it.id).put("name",it.name).put("phone",it.phone).put("address",it.address).put("gstNumber",it.gstNumber).put("active",it.active))}})
   root.put("meterReadings",JSONArray().apply{meters.forEach{put(JSONObject().put("id",it.id).put("meterId",it.meterId).put("date",it.readingDate).put("start",it.startReading).put("end",it.endReading).put("consumption",it.consumption).put("remarks",it.remarks))}})
   root.put("machines",JSONArray().apply{machines.forEach{put(JSONObject().put("id",it.id).put("code",it.machineCode).put("name",it.name).put("type",it.machineType).put("location",it.location).put("status",it.status).put("notes",it.notes).put("lineId",it.lineId))}})
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
  if(uri!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ReportExport.csv(production,packing,dispatches,meters,dispatchBatches,dispatchLines,machineEvents,qualityInspections)) } ?: error("Could not open export destination") }.onSuccess { message="CSV report exported" }.onFailure { message="Export failed: "+it.message }
 }
 MaterialTheme(colorScheme=lightColorScheme(primary=Blue,secondary=Cyan,background=Page,surface=Color.White,outline=Border)){
 Box(Modifier.fillMaxSize()) {
 if (isCheckingSession) {
  Box(Modifier.fillMaxSize().background(Page), contentAlignment = Alignment.Center) {
   CircularProgressIndicator(color = Blue)
  }
 } else if (session == null) {
  StartupLoginScreen(
   isOnline = isServerOnline(context) && FirebaseApp.getApps(context).isNotEmpty(),
   onFirebaseSignIn = { email, pass, onError ->
    scope.launch {
     runCatching {
      require(isServerOnline(context)) { "No internet connection. Please connect to internet to sign in." }
      FactoryAuthRepository(context).signIn(email, pass)
     }.onSuccess {
      session = it
      message = "Signed in as ${it.role.uppercase()} · Live Cloud Connected"
      SyncScheduler.runNow(context)
     }.onFailure { err ->
      onError(err.message ?: "Sign-in failed. Please check credentials or network.")
     }
    }
   },
   onRetryConnection = {
    isCheckingSession = true
    scope.launch {
     kotlinx.coroutines.delay(200)
     isCheckingSession = false
    }
   }
  )
 } else {
 Scaffold(topBar={TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=RoundedCornerShape(10.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,Border),modifier=Modifier.size(42.dp)){Box(contentAlignment=Alignment.Center,modifier=Modifier.padding(2.dp)){androidx.compose.foundation.Image(painter=androidx.compose.ui.res.painterResource(R.drawable.tck_brand),contentDescription="TCK Logo",modifier=Modifier.fillMaxSize())}};Column{Text("TCK Bottle Manufacturing",fontWeight=FontWeight.ExtraBold,color=Navy,fontSize=16.sp,maxLines=1);Text("MANUFACTURING OPERATIONS",fontSize=9.sp,color=Muted,letterSpacing=1.2.sp)} }},actions={
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
  Surface(
   onClick={},
   shape=RoundedCornerShape(20.dp),
   color=when(session?.role?.lowercase()){"admin","owner"->Color(0xFFF3E8FF);"manager"->Color(0xFFE0F2FE);"production_staff","operator"->Color(0xFFDCFCE7);else->Color(0xFFEFF6FF)},
   border=androidx.compose.foundation.BorderStroke(1.dp,when(session?.role?.lowercase()){"admin","owner"->Color(0xFFC084FC);"manager"->Cyan;"production_staff","operator"->Color(0xFF86EFAC);else->Color(0xFFBFDBFE)})
  ){
   Row(Modifier.padding(horizontal=9.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
    Box(Modifier.size(7.dp).background(if(FirebaseApp.getApps(context).isNotEmpty())Color(0xFF16804A) else Color(0xFFEAB308),RoundedCornerShape(4.dp)))
    Text(session?.role?.uppercase()?:"ADMIN",fontSize=10.sp,fontWeight=FontWeight.ExtraBold,color=when(session?.role?.lowercase()){"admin","owner"->Color(0xFF6B21A8);"manager"->Color(0xFF0369A1);"production_staff","operator"->Color(0xFF15803D);else->Blue})
   }
  }
  IconButton(
   onClick={
    FactoryAuthRepository(context).signOut()
    session=null
    message="Signed out"
   },
   modifier=Modifier.size(38.dp).background(Color(0xFFFEE2E2),RoundedCornerShape(10.dp))
  ){
   Icon(Icons.Default.Logout,contentDescription="Sign Out",tint=Color(0xFFDC2626),modifier=Modifier.size(18.dp))
  }
 }
},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.White))},bottomBar={NavigationBar(containerColor=Color.White,tonalElevation=8.dp){listOf(PageTab.HOME,PageTab.PRODUCTION,PageTab.PACKING,PageTab.STOCK,PageTab.DISPATCH,PageTab.REPORTS).forEach{t->NavigationBarItem(selected=tab==t,onClick={setTab(t)},icon={Icon(when(t){
 PageTab.HOME->Icons.Default.Home
 PageTab.PRODUCTION->Icons.Default.Build
 PageTab.PACKING->Icons.Default.Inventory2
 PageTab.STOCK->Icons.Default.Warehouse
 PageTab.DISPATCH->Icons.Default.LocalShipping
 else->Icons.Default.BarChart
},null)},label={Text(when(t){
 PageTab.HOME->"Home"
 PageTab.PRODUCTION->"Production"
 PageTab.PACKING->"Packing"
 PageTab.STOCK->"Stock"
 PageTab.DISPATCH->"Dispatch"
 else->"Reports"
},fontSize=10.sp,fontWeight=if(tab==t)FontWeight.Bold else FontWeight.Normal,maxLines=1)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Navy,selectedTextColor=Navy,indicatorColor=Color(0xFFDCEEFF),unselectedIconColor=Muted,unselectedTextColor=Muted))}}},containerColor=Page){padding->
 Box(Modifier.fillMaxSize().padding(padding)){
 when(tab){

 PageTab.HOME->{
  val todayProduction=production.filter{it.productionDate==date}
  val todayPacking=packing.filter{it.packingDate==date}
  val todayMeters=meters.filter{it.readingDate==date&&it.status=="COMPLETED"}
  val produced=todayProduction.sumOf{it.producedBottles}
  val good=todayProduction.sumOf{it.goodBottles}
  val rejected=todayProduction.sumOf{it.rejectedBottles}
  val packedToday=todayPacking.sumOf{it.completePacks}
  val dispatchedToday=dispatches.filter{it.dispatchDate==date}.sumOf{it.packsDispatched}+
   dispatchLines.filter{line->line.batchId in dispatchBatches.filter{it.status=="POSTED"&&it.dispatchDate==date}.map{it.id}}.sumOf{it.packs}
  val cloudItems=cloudStockSnapshot?.items?.filter{it.active}.orEmpty()
  val totalAvailablePacks=cloudItems.sumOf{it.completePacks}
  val totalPackedBottles=cloudItems.sumOf{it.completePacks*it.bottlesPerPack}
  val totalUnpackedBottles=cloudItems.sumOf{it.unpackedGoodBottles}
  val totalLooseBottles=cloudItems.sumOf{it.looseBottles}
  val totalGoodBottles=totalPackedBottles+totalUnpackedBottles+totalLooseBottles
  var quickOrderProductId by remember{mutableStateOf(cloudItems.firstOrNull()?.productId.orEmpty())}
  var quickOrderPacks by remember{mutableStateOf("1")}
  var quickOrderMenuExpanded by remember{mutableStateOf(false)}
  val selectedOrderProduct=cloudItems.firstOrNull{it.productId==quickOrderProductId}?:cloudItems.firstOrNull()
  val requestedPacks=quickOrderPacks.toIntOrNull()?.coerceAtLeast(0)?:0
  val selectedAvailablePacks=selectedOrderProduct?.completePacks?:0
  val orderShortage=(requestedPacks-selectedAvailablePacks).coerceAtLeast(0)
  val activeLinesCount=productionLines.count{it.status=="ACTIVE"}
  val runningMachinesCount=machines.count{it.status=="RUNNING"}
  val alertMachines=machines.filter{it.status in setOf("STOPPED","DOWN","MAINTENANCE")}
  LazyColumn(
   Modifier.fillMaxSize().background(Color(0xFFF3F7FC)),
   contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp),
   verticalArrangement=Arrangement.spacedBy(12.dp)
  ){
   item{
    Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
     Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
      Text("Factory",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
      Text(java.text.SimpleDateFormat("EEE, dd MMM yyyy",Locale.US).format(Date()),fontSize=11.sp,color=Muted)
     }
     TextButton(enabled=session?.isCloud==true&&!cloudStockLoading,onClick={
      cloudStockLoading=true;cloudStockError=null
      scope.launch{try{cloudStockSnapshot=CloudInventoryRepository(context).fetchStock(session!!)}catch(e:Exception){cloudStockError=e.message?: "Stock refresh failed"}finally{cloudStockLoading=false}}
     }){Text(if(cloudStockLoading)"Loading…" else "Refresh")}
    }
   }
   // CURRENT STOCK ONLY — server-authoritative balance
   item{
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Text("Current Stock",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Navy)
      if(cloudStockError!=null)Text("Refresh failed: $cloudStockError",fontSize=11.sp,color=Color(0xFFB42336))
      if(cloudStockSnapshot==null){
       Text(if(cloudStockLoading)"Loading server stock…" else if(session?.isCloud!=true)"Sign in to view live stock" else "Server stock unavailable",fontSize=12.sp,color=Muted)
      }else{
       Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Column(Modifier.weight(1f).background(Color(0xFFEAF3FF),RoundedCornerShape(10.dp)).padding(9.dp)){
         Text("Good bottles",fontSize=9.sp,color=Muted);Text(totalGoodBottles.toString(),fontSize=18.sp,fontWeight=FontWeight.Bold,color=Navy)
        }
        Column(Modifier.weight(1f).background(Color(0xFFE8F7F0),RoundedCornerShape(10.dp)).padding(9.dp)){
         Text("Packed packs",fontSize=9.sp,color=Muted);Text(totalAvailablePacks.toString(),fontSize=18.sp,fontWeight=FontWeight.Bold,color=Navy)
        }
        Column(Modifier.weight(1f).background(Color(0xFFFFF1DE),RoundedCornerShape(10.dp)).padding(9.dp)){
         Text("Unpacked",fontSize=9.sp,color=Muted);Text(totalUnpackedBottles.toString(),fontSize=18.sp,fontWeight=FontWeight.Bold,color=Navy)
        }
       }
       Text("Bottle-wise stock",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Navy)
       Row(Modifier.fillMaxWidth().background(Color(0xFFF0F4FA),RoundedCornerShape(7.dp)).padding(horizontal=7.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Text("Product",Modifier.weight(1.55f),fontSize=9.sp,fontWeight=FontWeight.Bold,color=Muted)
        Text("Total",Modifier.weight(.75f),fontSize=9.sp,fontWeight=FontWeight.Bold,color=Muted,textAlign=TextAlign.End)
        Text("Packs",Modifier.weight(.65f),fontSize=9.sp,fontWeight=FontWeight.Bold,color=Muted,textAlign=TextAlign.End)
        Text("Unpack",Modifier.weight(.75f),fontSize=9.sp,fontWeight=FontWeight.Bold,color=Muted,textAlign=TextAlign.End)
       }
       cloudItems.forEachIndexed{index,stock->
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
         Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
          Column(Modifier.weight(1.55f),verticalArrangement=Arrangement.spacedBy(1.dp)){
           Text(stock.name,fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Navy,maxLines=2)
           Text("${stock.sizeLabel} · ${stock.colour}",fontSize=9.sp,color=Muted,maxLines=1)
          }
          Text((stock.completePacks*stock.bottlesPerPack+stock.unpackedGoodBottles+stock.looseBottles).toString(),Modifier.weight(.75f),fontSize=11.sp,fontWeight=FontWeight.Medium,color=Navy,textAlign=TextAlign.End)
          Text(stock.completePacks.toString(),Modifier.weight(.65f),fontSize=11.sp,fontWeight=FontWeight.Bold,color=Navy,textAlign=TextAlign.End)
          Text(stock.unpackedGoodBottles.toString(),Modifier.weight(.75f),fontSize=11.sp,color=Color(0xFF9B5B00),textAlign=TextAlign.End)
         }
         if(index<cloudItems.lastIndex)HorizontalDivider(color=Border)
        }
       }
       Text("Total packed bottles: $totalPackedBottles  ·  Loose: $totalLooseBottles",fontSize=10.sp,color=Muted)
       Text("Server balance · ${cloudStockSnapshot?.serverTime?: "timestamp unavailable"}",fontSize=9.sp,color=Muted)
      }
      TextButton(onClick={setTab(PageTab.STOCK)}){Text("Full stock ledger →")}
     }
    }
   }
   // ORDER CHECK (not daily progress)
   item{
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Text("Order Check",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)
      if(cloudItems.isEmpty())Text("Load server stock to check order availability.",fontSize=11.sp,color=Muted)
      else{
       Box{
        OutlinedButton(onClick={quickOrderMenuExpanded=true},modifier=Modifier.fillMaxWidth()){
         Text(selectedOrderProduct?.let{"${it.name} · ${it.sizeLabel} · ${it.colour}"}?: "Select product",Modifier.weight(1f),color=Navy,maxLines=1)
         Icon(Icons.Default.ArrowDropDown,null)
        }
        DropdownMenu(expanded=quickOrderMenuExpanded,onDismissRequest={quickOrderMenuExpanded=false}){
         cloudItems.forEach{product->DropdownMenuItem(text={Text("${product.name} · ${product.sizeLabel} · ${product.colour}")},onClick={quickOrderProductId=product.productId;quickOrderMenuExpanded=false})}
        }
       }
       OutlinedTextField(value=quickOrderPacks,onValueChange={quickOrderPacks=it.filter{ch->ch.isDigit()}.take(7)},modifier=Modifier.fillMaxWidth(),label={Text("Order quantity (packs)")},singleLine=true)
       Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Column(Modifier.weight(1f).background(Color(0xFFEAF3FF),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Available",fontSize=9.sp,color=Muted);Text("$selectedAvailablePacks",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)}
        Column(Modifier.weight(1f).background(Color(0xFFFFF1DE),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Shortage",fontSize=9.sp,color=Muted);Text("$orderShortage packs",fontSize=17.sp,fontWeight=FontWeight.Bold,color=if(orderShortage==0)Color(0xFF16804A) else Color(0xFF9B5B00))}
       }
       Text(if(requestedPacks<=0)"Enter packs required." else if(orderShortage==0)"Ready to dispatch" else "Production / packing required",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=if(orderShortage==0)Color(0xFF16804A)else Color(0xFF9B5B00))
      }
     }
    }
   }
   // TODAY PROGRESS — today's activity only, separate from stock
   item{
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Text("Today Progress",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);TextButton(onClick={setTab(PageTab.REPORTS)}){Text("Reports →")}}
      Text("Production, packing and dispatch for today",fontSize=10.sp,color=Muted)
      Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
       Column(Modifier.weight(1f).background(Color(0xFFEAF3FF),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Produced",fontSize=9.sp,color=Muted);Text(produced.toString(),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Good: $good",fontSize=9.sp,color=Muted)}
       Column(Modifier.weight(1f).background(Color(0xFFE8F7F0),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Packed",fontSize=9.sp,color=Muted);Text(packedToday.toString(),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Packs today",fontSize=9.sp,color=Muted)}
       Column(Modifier.weight(1f).background(Color(0xFFF0EAFE),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Dispatched",fontSize=9.sp,color=Muted);Text(dispatchedToday.toString(),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Packs today",fontSize=9.sp,color=Muted)}
      }
      Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
       Column(Modifier.weight(1f).background(Color(0xFFFFF1DE),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Rejected",fontSize=9.sp,color=Muted);Text(rejected.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)}
       Column(Modifier.weight(1f).background(Color(0xFFEAF3FF),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Electricity",fontSize=9.sp,color=Muted);Text(String.format(Locale.US,"%.1f",todayMeters.sumOf{it.consumption}),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Units",fontSize=9.sp,color=Muted)}
       Column(Modifier.weight(1f).background(Color(0xFFF3F4F6),RoundedCornerShape(10.dp)).padding(9.dp)){Text("Runs",fontSize=9.sp,color=Muted);Text(todayProduction.size.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)}
      }
     }
    }
   }
   // SHOP FLOOR
   item{
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border),onClick={setTab(PageTab.MACHINES)}){
     Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Text("Shop-floor status",Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Bold,color=Navy);TextButton(onClick={setTab(PageTab.MACHINES)}){Text("Manage →")}}
      Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
       Column(Modifier.weight(1f).background(Color(0xFFEAF3FF),RoundedCornerShape(9.dp)).padding(8.dp)){Text("Active lines",fontSize=9.sp,color=Muted);Text(activeLinesCount.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)}
       Column(Modifier.weight(1f).background(Color(0xFFE8F7F0),RoundedCornerShape(9.dp)).padding(8.dp)){Text("Running",fontSize=9.sp,color=Muted);Text(runningMachinesCount.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Color(0xFF16804A))}
       Column(Modifier.weight(1f).background(if(alertMachines.isNotEmpty())Color(0xFFFFF1DE)else Color(0xFFE8F7F0),RoundedCornerShape(9.dp)).padding(8.dp)){Text("Alerts",fontSize=9.sp,color=Muted);Text(alertMachines.size.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=if(alertMachines.isNotEmpty())Color(0xFF9B5B00)else Color(0xFF16804A))}
      }
     }
    }
   }
   // COMPLETE ACTIVE PRODUCT CATALOGUE
   item{
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Factory Products",fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy);Text("${active.size} active products",fontSize=10.sp,color=Muted)};TextButton(onClick={setTab(PageTab.PRODUCTS)}){Text("Manage →")}}
   }
   if(active.isEmpty())item{EmptyDashboardState("No active products","Add bottle products in Product Master.",Icons.Default.WaterDrop)}
   else items(active,key={it.id}){product->
    BottleProductCard(name=product.name,capacity=product.sizeLabel,packSizeLabel=if(product.bottlesPerPack>0)"${product.bottlesPerPack} bottles per pack"else"",active=product.active,bottleType="${product.sizeLabel} ${product.colour} ${product.name}",iconName=product.iconName,imageUri=product.imageUri,colour=product.colour,onClick={editingProduct=product;dialog="product"})
   }
  }
 }

 PageTab.PRODUCTS->{
  val filteredByStatus = when(productSubTab) {
   1 -> products.filter{it.active}
   2 -> products.filter{!it.active}
   else -> products
  }
  val shown = filteredByStatus.filter{(it.name+" "+it.sizeLabel+" "+it.colour).contains(productSearch,true)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{
    Row(verticalAlignment=Alignment.CenterVertically){
     Column(Modifier.weight(1f)){
      Text("PRODUCT MASTER",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp)
      Text("Products",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
      Text(products.count{it.active}.toString()+" active products · "+products.count{!it.active}+" deactivated",fontSize=12.sp,color=Muted)
     }
     Button(onClick={editingProduct=null;dialog="product"},shape=RoundedCornerShape(14.dp)){
      Icon(Icons.Default.Add,null)
      Spacer(Modifier.width(5.dp))
      Text("Add product")
     }
    }
   }
   item{
    OutlinedTextField(
     productSearch,
     {productSearch=it},
     modifier=Modifier.fillMaxWidth(),
     singleLine=true,
     shape=RoundedCornerShape(16.dp),
     leadingIcon={Icon(Icons.Default.Search,null)},
     placeholder={Text("Search name, size or colour")},
     label={Text("Find a product")}
    )
   }
   item{
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
     FilterChip(
      selected=productSubTab==0,
      onClick={productSubTab=0},
      label={Text("All (${products.size})",fontSize=11.sp,fontWeight=FontWeight.Bold)},
      shape=RoundedCornerShape(20.dp)
     )
     FilterChip(
      selected=productSubTab==1,
      onClick={productSubTab=1},
      label={Text("Active (${products.count{it.active}})",fontSize=11.sp,fontWeight=FontWeight.Bold)},
      shape=RoundedCornerShape(20.dp)
     )
     FilterChip(
      selected=productSubTab==2,
      onClick={productSubTab=2},
      label={Text("Deactivated (${products.count{!it.active}})",fontSize=11.sp,fontWeight=FontWeight.Bold)},
      shape=RoundedCornerShape(20.dp)
     )
    }
   }
   if(shown.isEmpty())item{EmptyDashboardState("No matching products","Try another search or filter.",Icons.Default.Search)}
   items(shown,key={it.id}){p->
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
       ProductThumbnail(imageUri=p.imageUri,iconName=p.iconName,colour=p.colour,bottleType="${p.sizeLabel} ${p.colour} ${p.name}",size=50.dp)
       Column(Modifier.weight(1f)){
        Text(p.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=16.sp)
        Text(p.sizeLabel+" · "+p.colour+if(p.bottlesPerPack>0)" · ${p.bottlesPerPack} per pack" else "",fontSize=12.sp,color=Muted)
       }
       Surface(color=if(p.active)Color(0xFFE8F7EE) else Color(0xFFFFF4E5),shape=RoundedCornerShape(30.dp)){
        Text(if(p.active)"ACTIVE" else "DEACTIVATED",modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(p.active)Color(0xFF16804A) else Color(0xFFB45309))
       }
      }
      HorizontalDivider(color=Border)
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End,modifier=Modifier.fillMaxWidth()){
       OutlinedButton(onClick={editingProduct=p;dialog="product"},shape=RoundedCornerShape(10.dp)){
        Icon(Icons.Default.Edit,null,modifier=Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text("Edit",fontSize=11.sp)
       }
       Spacer(Modifier.width(8.dp))
       if(p.active){
        TextButton(onClick={
         scope.launch{
          runCatching{
           dao.updateProductWithHistory(p.id,p.name,p.sizeLabel,p.colour,p.bottlesPerPack,false,session?.uid?:"local","Product deactivated",session?.organisationId?:"tck-factory-01",p.iconName,p.imageUri)
          }.onSuccess{
           SyncScheduler.runNow(context)
           message="${p.name} deactivated"
          }.onFailure{message=it.message}
         }
        }){
         Icon(Icons.Default.Block,null,tint=Color(0xFFB45309),modifier=Modifier.size(15.dp))
         Spacer(Modifier.width(4.dp))
         Text("Deactivate",color=Color(0xFFB45309),fontSize=11.sp)
        }
       }else{
        TextButton(onClick={
         scope.launch{
          runCatching{
           dao.updateProductWithHistory(p.id,p.name,p.sizeLabel,p.colour,p.bottlesPerPack,true,session?.uid?:"local","Product activated",session?.organisationId?:"tck-factory-01",p.iconName,p.imageUri)
          }.onSuccess{
           SyncScheduler.runNow(context)
           message="${p.name} activated"
          }.onFailure{message=it.message}
         }
        }){
         Icon(Icons.Default.CheckCircle,null,tint=Color(0xFF16804A),modifier=Modifier.size(15.dp))
         Spacer(Modifier.width(4.dp))
         Text("Activate",color=Color(0xFF16804A),fontSize=11.sp)
        }
       }
       Spacer(Modifier.width(4.dp))
       IconButton(onClick={deletingProduct=p}){
        Icon(Icons.Default.Archive,contentDescription="Archive product",tint=Color(0xFFDC2626),modifier=Modifier.size(18.dp))
       }
      }
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
      if(session?.role?.let{it in setOf("owner","admin","manager")}==true && entry.cloudOperationKey.isNotBlank())
       TextButton(onClick={reversalProductionId=entry.id;dialog="reverseProduction"}){Icon(Icons.Default.Undo,null);Spacer(Modifier.width(4.dp));Text("Request reversal")}

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
  val sorted=meters.sortedWith(compareByDescending<MeterReadingEntity>{it.readingDate}.thenByDescending{it.createdAt})
  val completedMeters=meters.filter{it.status=="COMPLETED"}
  val totalUnits=completedMeters.filter{it.readingDate.startsWith(date.substring(0,7))}.sumOf{it.consumption}
  val latestCompleted=sorted.firstOrNull{it.status=="COMPLETED"}
  val pendingReading=meters.firstOrNull{it.status=="PENDING"}

  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.weight(1f)){
       Text("ENERGY MONITORING",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp)
       Text("Electricity",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
       Text("Morning & evening meter readings · daily unit calculation",fontSize=12.sp,color=Muted)
      }
      OutlinedButton(onClick={meterDialogMode="FULL";selectedMeterReading=null;dialog="meter"},shape=RoundedCornerShape(12.dp)){
       Icon(Icons.Default.Add,null,modifier=Modifier.size(16.dp))
       Spacer(Modifier.width(4.dp))
       Text("Full Entry",fontSize=11.sp)
      }
     }
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      Button(
       onClick={meterDialogMode="MORNING";selectedMeterReading=null;dialog="meter"},
       modifier=Modifier.weight(1f),
       shape=RoundedCornerShape(12.dp),
       colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF0284C7))
      ){
       Icon(Icons.Default.WbSunny,null,modifier=Modifier.size(16.dp))
       Spacer(Modifier.width(6.dp))
       Text("☀️ Day Start",fontSize=12.sp,fontWeight=FontWeight.Bold)
      }
      Button(
       onClick={meterDialogMode="EVENING";selectedMeterReading=pendingReading;dialog="meter"},
       modifier=Modifier.weight(1f),
       shape=RoundedCornerShape(12.dp),
       colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF4338CA))
      ){
       Icon(Icons.Default.Schedule,null,modifier=Modifier.size(16.dp))
       Spacer(Modifier.width(6.dp))
       Text("🌙 Day End",fontSize=12.sp,fontWeight=FontWeight.Bold)
      }
     }
    }
   }

   if(pendingReading!=null&&pendingReading.readingDate<date){
    item{
     Surface(
      color=Color(0xFFFEF3C7),
      shape=RoundedCornerShape(16.dp),
      border=androidx.compose.foundation.BorderStroke(1.5.dp,Color(0xFFF59E0B))
     ){
      Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
       Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Icon(Icons.Default.WarningAmber,null,tint=Color(0xFFB45309),modifier=Modifier.size(24.dp))
        Column(Modifier.weight(1f)){
         Text("Missed Evening Reading!",fontWeight=FontWeight.Bold,color=Color(0xFF92400E),fontSize=14.sp)
         Text("${pendingReading.readingDate} started at ${pendingReading.startReading} kWh, but evening end reading was not entered.",fontSize=11.sp,color=Color(0xFF78350F))
        }
       }
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Button(
         onClick={meterDialogMode="EVENING";selectedMeterReading=pendingReading;dialog="meter"},
         modifier=Modifier.weight(1f),
         shape=RoundedCornerShape(10.dp),
         colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFD97706))
        ){
         Text("Complete End Reading",fontSize=11.sp,fontWeight=FontWeight.Bold)
        }
        OutlinedButton(
         onClick={meterDialogMode="CATCH_UP";selectedMeterReading=pendingReading;dialog="meter"},
         modifier=Modifier.weight(1f),
         shape=RoundedCornerShape(10.dp)
        ){
         Text("Close & Start Today",fontSize=11.sp,fontWeight=FontWeight.Bold)
        }
       }
      }
     }
    }
   }

   if(pendingReading!=null&&pendingReading.readingDate==date){
    item{
     Surface(
      color=Color(0xFFE0F2FE),
      shape=RoundedCornerShape(16.dp),
      border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF38BDF8))
     ){
      Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
       Surface(color=Color(0xFF0284C7),shape=RoundedCornerShape(50)){
        Icon(Icons.Default.WbSunny,null,tint=Color.White,modifier=Modifier.padding(8.dp).size(20.dp))
       }
       Column(Modifier.weight(1f)){
        Text("Today's Reading in Progress",fontWeight=FontWeight.Bold,color=Color(0xFF0369A1),fontSize=13.sp)
        Text("Start reading: ${pendingReading.startReading} kWh · Awaiting evening end reading",fontSize=11.sp,color=Color(0xFF075985))
       }
       Button(
        onClick={meterDialogMode="EVENING";selectedMeterReading=pendingReading;dialog="meter"},
        shape=RoundedCornerShape(10.dp),
        colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF0284C7))
       ){
        Text("Enter End",fontSize=11.sp)
       }
      }
     }
    }
   }

   item{
    Surface(color=Navy,shape=RoundedCornerShape(20.dp)){
     Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text("CURRENT MONTH",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.3.sp)
      Text(String.format(Locale.US,"%.1f units",totalUnits),fontSize=31.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
      Text("Total from finalized meter readings",fontSize=11.sp,color=Color(0xFFD4E2F0))
      HorizontalDivider(color=Color(0xFF345574))
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
       Text(if(latestCompleted!=null)"Latest: "+latestCompleted.meterId+" · "+latestCompleted.readingDate else "No completed readings yet",fontSize=12.sp,color=Color.White)
       if(latestCompleted!=null)Text("End: ${latestCompleted.endReading} kWh",fontSize=11.sp,color=Cyan)
      }
     }
    }
   }

   item{Text("Reading history",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   if(sorted.isEmpty())item{EmptyDashboardState("No meter readings yet","Add morning and evening readings to calculate electricity usage.",Icons.Default.Bolt)}

   items(sorted,key={it.id}){entry->
    val isPending=entry.status=="PENDING"
    Surface(
     color=if(isPending) Color(0xFFFFFBEB) else Color.White,
     shape=RoundedCornerShape(17.dp),
     border=androidx.compose.foundation.BorderStroke(1.dp,if(isPending) Color(0xFFFDE68A) else Border)
    ){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Surface(color=if(isPending) Color(0xFFFEF3C7) else Color(0xFFFFF4DF),shape=RoundedCornerShape(12.dp)){
        Icon(if(isPending) Icons.Default.Schedule else Icons.Default.Bolt,null,tint=if(isPending) Color(0xFFD97706) else Color(0xFF9B5B00),modifier=Modifier.padding(10.dp).size(22.dp))
       }
       Column(Modifier.weight(1f).padding(start=10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
         Text(entry.meterId,fontWeight=FontWeight.Bold,color=Navy)
         if(isPending){
          Surface(color=Color(0xFFFDE68A),shape=RoundedCornerShape(6.dp)){
           Text("DAY IN PROGRESS",fontSize=9.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFF92400E),modifier=Modifier.padding(horizontal=5.dp,vertical=2.dp))
          }
         }
        }
        Text(entry.readingDate,fontSize=11.sp,color=Muted)
       }
       Column(horizontalAlignment=Alignment.End){
        if(isPending){
         Button(
          onClick={meterDialogMode="EVENING";selectedMeterReading=entry;dialog="meter"},
          shape=RoundedCornerShape(8.dp),
          colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFD97706)),
          contentPadding=PaddingValues(horizontal=10.dp,vertical=4.dp)
         ){
          Text("Enter End",fontSize=10.sp)
         }
        }else{
         Text(String.format(Locale.US,"%.1f",entry.consumption),fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
         Text("units",fontSize=10.sp,color=Muted)
        }
       }
      }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
       StockMiniMetric("Start",entry.startReading.toInt().toString(),Modifier.weight(1f))
       StockMiniMetric("End",if(isPending)"Pending" else entry.endReading.toInt().toString(),Modifier.weight(1f))
       StockMiniMetric("Used",if(isPending)"—" else entry.consumption.toInt().toString(),Modifier.weight(1f))
      }
      if(entry.remarks.isNotBlank())Text(entry.remarks,fontSize=11.sp,color=Muted)
     }
    }
   }
  }
 }
 PageTab.CUSTOMERS->{
  val shown=customers.filter{(it.name+" "+it.phone+" "+it.address+" "+it.gstNumber).contains(customerSearch,true)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("ARCHIVED CUSTOMERS (${archivedCustomers.size})",
    fontSize=11.sp,fontWeight=FontWeight.Bold,color=Muted)
if(archivedCustomers.isNotEmpty()){
    archivedCustomers.forEach { archived ->
        Row(
            Modifier.fillMaxWidth().padding(vertical=5.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Column(Modifier.weight(1f)){
                Text(archived.name,fontWeight=FontWeight.SemiBold,color=Navy)
                Text(archived.phone,fontSize=11.sp,color=Muted)
            }
            TextButton(onClick={
                scope.launch {
                    runCatching {
                        require(roleAllows(session,FactoryAction.MANAGE_CUSTOMERS)){
                            "Your role cannot restore customers"
                        }
                        dao.updateCustomer(
                            archived.id,archived.name,archived.phone,
                            archived.address,archived.gstNumber,true
                        )
                        dao.enqueueSyncEvent(SyncOutboxEntity(
                            eventKey="customer-restore:${archived.id}",
                            organisationId=session?.organisationId?:"local-org",
                            eventType="CUSTOMER_RESTORED",
                            payloadJson=JSONObject()
                                .put("customerId",archived.id)
                                .put("name",archived.name).toString()
                        ))
                        dao.insertAudit(AuditEventEntity(
                            eventType="CUSTOMER_RESTORED",
                            entityType="customer",
                            entityId=archived.id.toString(),
                            beforeJson="archived",
                            afterJson="active",
                            reason="Customer restored; transaction history retained",
                            actorId=session?.uid?:"local"
                        ))
                        SyncScheduler.runNow(context)
                        message="Customer restored"
                    }.onFailure { message=it.message }
                }
            }) { Text("Restore") }
        }
    }
}else{
    Text("No archived customers",fontSize=12.sp,color=Muted)
}

Text("CUSTOMER DIRECTORY",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Customers",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(customers.size.toString()+" saved customer records",fontSize=12.sp,color=Muted)};Button(onClick={dialog="customer"},shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("Add")}}}
   item{OutlinedTextField(customerSearch,{customerSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(15.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search name, phone, address or GST")})}
   if(shown.isEmpty())item{EmptyDashboardState("No customers found","Add a customer before posting a dispatch.",Icons.Default.People)}
   items(shown,key={it.id}){customer->
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
      Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(15.dp)){Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){Text(customer.name.trim().take(1).uppercase(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Blue)}}
      Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(customer.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=15.sp);Text(customer.phone.ifBlank{"Phone not provided"},fontSize=12.sp,color=Blue);if(customer.address.isNotBlank())Text(customer.address,fontSize=11.sp,color=Muted);if(customer.gstNumber.isNotBlank())Text("GST · "+customer.gstNumber,fontSize=10.sp,color=Muted)}
      Column(horizontalAlignment=Alignment.End){TextButton(onClick={editingCustomer=customer;dialog="customerEdit"}){Icon(Icons.Default.Edit,null);Text("Edit")};TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_CUSTOMERS)){"Your role cannot archive customers"};dao.updateCustomer(customer.id,customer.name,customer.phone,customer.address,customer.gstNumber,false);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="customer-archive:${customer.id}",organisationId=session?.organisationId?:"local-org",eventType="CUSTOMER_ARCHIVED",payloadJson=JSONObject().put("customerId",customer.id).put("name",customer.name).toString()));dao.insertAudit(AuditEventEntity(eventType="CUSTOMER_ARCHIVED",entityType="customer",entityId=customer.id.toString(),beforeJson="active",afterJson="archived",reason="Customer archived; transaction history retained",actorId=session?.uid?:"local"));SyncScheduler.runNow(context);message="Customer archived"}.onFailure{message=it.message}}}){Text("Archive")};TextButton(onClick={dialog="dispatch"}){Text("Dispatch")}}
     }
    }
   }
  }
 }
 PageTab.STOCK->{
  val totalPacked=packing.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.completePacks}}
  val loosePacked=packing.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.looseBottles}}
  val legacyDispatched=dispatches.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.packsDispatched}}
  val validBatchIds=dispatchBatches.filter{it.status=="POSTED"}.map{it.id}.toSet()
  val postedLines=dispatchLines.filter{it.batchId in validBatchIds}
  val batchedDispatched=postedLines.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{it.packs}}
  val looseDispatched=postedLines.groupBy{it.productId}.mapValues{(_,rows)->rows.sumOf{(it.bottles-it.packs*it.packSizeSnapshot).coerceAtLeast(0)}}
  val totalAvailable=active.sumOf{((totalPacked[it.id]?:0)-(legacyDispatched[it.id]?:0)-(batchedDispatched[it.id]?:0)).coerceAtLeast(0)}
  val totalLooseAvailable=active.sumOf{((loosePacked[it.id]?:0)-(looseDispatched[it.id]?:0)).coerceAtLeast(0)}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Text("INVENTORY CONTROL",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Finished stock",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Complete packs and loose bottles ready for dispatch",fontSize=12.sp,color=Muted)}
   if(session?.isCloud==true&&roleAllows(session,FactoryAction.ADJUST_STOCK)){
    item{
     Button(onClick={
      stockAdjustmentProduct=active.firstOrNull()
      stockAdjustmentMode="ADD"
      stockAdjustmentUnit="PACK"
      stockAdjustmentQuantity="1"
      stockAdjustmentReason=""
      dialog="stockAdjustment"
     },modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){
      Icon(Icons.Default.Edit,null);Spacer(Modifier.width(8.dp));Text("Adjust server stock")
     }
    }
   }

   if(session?.isCloud==true){
    item{
     Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF9CCFEB))){
      Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
       Text("AUTHORITATIVE CLOUD INVENTORY",fontWeight=FontWeight.ExtraBold,color=Navy,fontSize=12.sp)
       Text(if(cloudStockSnapshot!=null)"Server snapshot · "+cloudStockSnapshot!!.serverTime else if(cloudStockLoading)"Loading server stock…" else "No confirmed server snapshot loaded. Local figures below are history only.",fontSize=11.sp,color=Muted)
       if(cloudStockError!=null)Text(cloudStockError!!,fontSize=11.sp,color=Color(0xFFB42318))
       Button(enabled=!cloudStockLoading,onClick={scope.launch{cloudStockLoading=true;cloudStockError=null;try{cloudStockSnapshot=CloudInventoryRepository(context).fetchStock(session!!)}catch(e:Exception){cloudStockError=e.message?:"Unable to load server stock"}finally{cloudStockLoading=false}}},shape=RoundedCornerShape(10.dp)){Icon(Icons.Default.CloudSync,null,modifier=Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text(if(cloudStockLoading)"Refreshing…" else "Refresh server stock")}
      }
     }
    }
    val authoritativeItems=cloudStockSnapshot?.items?.filter{it.active&& (it.name+" "+it.sizeLabel+" "+it.colour).contains(stockSearch,true)}.orEmpty()
    if(authoritativeItems.isEmpty()){
     item{EmptyDashboardState(if(cloudStockLoading)"Loading server inventory" else "No server stock records",if(cloudStockError!=null)"Refresh failed. Check connectivity and factory membership." else "Provision products and post confirmed production/packing transactions to populate shared inventory.",Icons.Default.CloudQueue)}
    }else{
     item{Text("Server-confirmed stock by product",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)}
     items(authoritativeItems,key={"cloud-"+it.productId}){item->
      Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF9CCFEB))){
       Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
         Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.Bold,color=Navy);Text(item.sizeLabel+" · "+item.colour+" · "+if(item.bottlesPerPack>0)"${item.bottlesPerPack} bottles/pack" else "Pack size not configured",fontSize=10.sp,color=Muted)}
         Column(horizontalAlignment=Alignment.End){Text(item.completePacks.toString(),fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("packs",fontSize=9.sp,color=Muted)}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){StockMiniMetric("Unpacked bottles",item.unpackedGoodBottles,Modifier.weight(1f));StockMiniMetric("Loose bottles",item.looseBottles,Modifier.weight(1f))}
       }
      }
     }
    }
    item{Text("Local device history · not authoritative",fontSize=16.sp,fontWeight=FontWeight.Bold,color=Color(0xFF9B5B00))}
   }
   item{Surface(color=Navy,shape=RoundedCornerShape(22.dp)){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(session?.isCloud==true)"SERVER AVAILABLE PACKS" else "TOTAL AVAILABLE PACKS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.2.sp);Text(if(session?.isCloud==true)cloudStockSnapshot?.items?.sumOf{it.completePacks}?.toString()?: "—" else totalAvailable.toString(),fontSize=36.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);Text(if(session?.isCloud==true)cloudStockSnapshot?.items?.count{it.active}?.toString()?.plus(" active server product types")?:"Server snapshot unavailable" else active.size.toString()+" active product types",fontSize=12.sp,color=Color(0xFFD4E2F0))}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric(if(session?.isCloud==true)"Server loose bottles" else "Available loose bottles",if(session?.isCloud==true)cloudStockSnapshot?.items?.sumOf{it.looseBottles}?.toString()?: "—" else totalLooseAvailable.toString(),Modifier.weight(1f));Metric(if(session?.isCloud==true)"Unpacked good bottles" else "Dispatched packs",if(session?.isCloud==true)cloudStockSnapshot?.items?.sumOf{it.unpackedGoodBottles}?.toString()?: "—" else (legacyDispatched.values.sum()+batchedDispatched.values.sum()).toString(),Modifier.weight(1f))}}
   item{
    val configuredCount=active.count{it.bottlesPerPack>0}
    val lowCount=active.count{p->((totalPacked[p.id]?:0)-(legacyDispatched[p.id]?:0)-(batchedDispatched[p.id]?:0)).coerceAtLeast(0)<=5}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     Surface(Modifier.weight(1f),color=Color(0xFFE8F7F0),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(12.dp)){Text("Configured products",fontSize=10.sp,color=Muted);Text(configuredCount.toString()+" / "+active.size,fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFF16804A))}}
     Surface(Modifier.weight(1f),color=if(lowCount>0)Color(0xFFFFF3DE) else Color(0xFFE8F7F0),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(12.dp)){Text("Low-stock alerts",fontSize=10.sp,color=Muted);Text(lowCount.toString(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=if(lowCount>0)Color(0xFF9B5B00) else Color(0xFF16804A))}}
    }
   }
   item{OutlinedTextField(stockSearch,{stockSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(15.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search product or bottle colour")},label={Text("Find stock")})}
   item{Text(if(session?.isCloud==true)"Local device history (not authoritative)" else "Stock by product",fontSize=19.sp,fontWeight=FontWeight.Bold,color=if(session?.isCloud==true)Color(0xFF9B5B00) else Navy)}
   val shownStock=active.filter{(it.name+" "+it.sizeLabel+" "+it.colour).contains(stockSearch,true)}
   if(shownStock.isEmpty())item{EmptyDashboardState("No matching stock","Try another product name, size or colour.",Icons.Default.Search)}
   items(shownStock,key={it.id}){p->
    val packedCount=totalPacked[p.id]?:0
    val sent=(legacyDispatched[p.id]?:0)+(batchedDispatched[p.id]?:0)
    val available=(packedCount-sent).coerceAtLeast(0)
    val looseSent=looseDispatched[p.id]?:0
    val looseAvailable=((loosePacked[p.id]?:0)-looseSent).coerceAtLeast(0)
    val configured=p.bottlesPerPack>0
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
       Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.WaterDrop,null,tint=Cyan,modifier=Modifier.padding(11.dp).size(24.dp))}
       Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=16.sp);Text(if(configured)p.bottlesPerPack.toString()+" bottles per pack" else "Pack size not configured",fontSize=11.sp,color=if(configured)Muted else Color(0xFF9B5B00))}
       Column(horizontalAlignment=Alignment.End){Text(available.toString(),fontSize=24.sp,fontWeight=FontWeight.ExtraBold,color=if(available<=5)Color(0xFFB45309) else Navy);Text("available packs",fontSize=9.sp,color=Muted)}
      }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Packed",packedCount,Modifier.weight(1f));StockMiniMetric("Dispatched",sent,Modifier.weight(1f));StockMiniMetric("Loose available",looseAvailable,Modifier.weight(1f))}
      if(looseSent>0)Text("Loose bottles dispatched: $looseSent",fontSize=10.sp,color=Muted)
      if(!configured)Text("Configure pack size in Products before posting packing or dispatch.",fontSize=11.sp,color=Color(0xFF9B5B00))
     }
    }
   }
  }
 }
 PageTab.MACHINES->{
  val now=System.currentTimeMillis();val windowStart=now-7L*24*60*60*1000
  val stoppedMillis=machineEvents.filter{it.eventType in setOf("DOWNTIME","MACHINE_STOPPED")&&(it.endedAt?:now)>windowStart}.sumOf{(minOf(it.endedAt?:now,now)-maxOf(it.startedAt,windowStart)).coerceAtLeast(0L)}
  val capacityMillis=machines.size.toLong()*7*24*60*60*1000
  val availability=if(capacityMillis>0)((capacityMillis-stoppedMillis).coerceAtLeast(0L)*100.0/capacityMillis).coerceIn(0.0,100.0) else 0.0

  Column(Modifier.fillMaxSize()){
   Surface(color=Color.White,tonalElevation=2.dp){
    TabRow(selectedTabIndex=machineSubTab,containerColor=Color.White,contentColor=Navy){
     Tab(selected=machineSubTab==0,onClick={machineSubTab=0},text={Text("Lines (${productionLines.size})",fontSize=11.sp,fontWeight=if(machineSubTab==0)FontWeight.Bold else FontWeight.Normal)})
     Tab(selected=machineSubTab==1,onClick={machineSubTab=1},text={Text("Machinery (${machines.size})",fontSize=11.sp,fontWeight=if(machineSubTab==1)FontWeight.Bold else FontWeight.Normal)})
     Tab(selected=machineSubTab==2,onClick={machineSubTab=2},text={Text("Maintenance",fontSize=11.sp,fontWeight=if(machineSubTab==2)FontWeight.Bold else FontWeight.Normal)})
     Tab(selected=machineSubTab==3,onClick={machineSubTab=3},text={Text("Downtime",fontSize=11.sp,fontWeight=if(machineSubTab==3)FontWeight.Bold else FontWeight.Normal)})
    }
   }
   when(machineSubTab){
    0->{
     LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      item{
       Row(verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
         Text("PRODUCTION INFRASTRUCTURE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp)
         Text("Production lines",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
         Text(productionLines.size.toString()+" operational line(s)",fontSize=12.sp,color=Muted)
        }
        Button(onClick={editingLine=null;dialog="line"},shape=RoundedCornerShape(13.dp)){
         Icon(Icons.Default.Add,null)
         Spacer(Modifier.width(4.dp))
         Text("Add line")
        }
       }
      }
      item{
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Metric("Active lines",productionLines.count{it.status=="ACTIVE"}.toString(),Modifier.weight(1f))
        Metric("Inactive",productionLines.count{it.status!="ACTIVE"}.toString(),Modifier.weight(1f))
        Metric("Assigned assets",machines.count{it.lineId!=null}.toString(),Modifier.weight(1f))
       }
      }
      if(productionLines.isEmpty()){
       item{EmptyDashboardState("No production lines","Create a production line to group machines and track production flow.",Icons.Default.PrecisionManufacturing)}
      }
      items(productionLines,key={it.id}){line->
       val lineMachines=machines.filter{it.lineId==line.id}
       Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
        Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
         Row(verticalAlignment=Alignment.CenterVertically){
          Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){
           Icon(Icons.Default.PrecisionManufacturing,null,tint=Blue,modifier=Modifier.padding(10.dp).size(24.dp))
          }
          Column(Modifier.weight(1f).padding(start=10.dp)){
           Text(line.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=16.sp)
           Text(line.lineCode+(if(line.description.isNotBlank()) " · "+line.description else ""),fontSize=11.sp,color=Muted)
          }
          Surface(color=if(line.status=="ACTIVE")Color(0xFFE8F7F0) else Color(0xFFE9EDF2),shape=RoundedCornerShape(30.dp)){
           Text(line.status,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(line.status=="ACTIVE")Color(0xFF16804A) else Muted)
          }
         }
         if(line.targetCapacity>0){
          Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
           Icon(Icons.Default.Speed,null,tint=Blue,modifier=Modifier.size(16.dp))
           Text("Target capacity: ${line.targetCapacity} ${line.capacityUnit.lowercase().replace('_',' ')}",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Navy)
          }
         }
         HorizontalDivider(color=Border)
         Text("Assigned equipment (${lineMachines.size})",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Muted)
         if(lineMachines.isEmpty()){
          Text("No machines currently assigned to this line. Assign equipment from the Machinery tab.",fontSize=11.sp,color=Color(0xFF9B5B00))
         } else {
          Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
           lineMachines.chunked(2).forEach{rowChunk->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
             rowChunk.forEach{m->
              Surface(modifier=Modifier.weight(1f),color=Page,shape=RoundedCornerShape(8.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
               Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                Icon(Icons.Default.Circle,null,tint=if(m.status=="RUNNING")Color(0xFF16804A) else Color(0xFFB45309),modifier=Modifier.size(8.dp))
                Text(m.name,fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Navy,maxLines=1)
               }
              }
             }
             if(rowChunk.size==1){
              Spacer(Modifier.weight(1f))
             }
            }
           }
          }
         }
         Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
          OutlinedButton(onClick={editingLine=line;dialog="line"},modifier=Modifier.weight(1f),shape=RoundedCornerShape(11.dp)){
           Icon(Icons.Default.Edit,null,modifier=Modifier.size(15.dp))
           Spacer(Modifier.width(4.dp))
           Text("Edit",fontSize=11.sp)
          }
          Button(onClick={
           scope.launch{
            runCatching{
             require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot change line status"}
             val nextStatus=if(line.status=="ACTIVE")"INACTIVE" else "ACTIVE"
             dao.updateProductionLine(line.id,line.name,line.description,line.targetCapacity,line.capacityUnit,nextStatus)
             SyncScheduler.runNow(context)
             message="Line status updated to $nextStatus"
            }.onFailure{message=it.message}
           }
          },modifier=Modifier.weight(1.2f),shape=RoundedCornerShape(11.dp),colors=ButtonDefaults.buttonColors(containerColor=if(line.status=="ACTIVE")Color(0xFFB45309) else Color(0xFF16804A))){
           Text(if(line.status=="ACTIVE")"Deactivate" else "Activate",fontSize=11.sp)
          }
          IconButton(onClick={deletingLine=line}){
           Icon(Icons.Default.DeleteOutline,null,tint=Color(0xFFB42318),modifier=Modifier.size(20.dp))
          }
         }
        }
       }
      }
     }
    }
    1->{
      val filteredMachines=machines.filter{
       (selectedLineFilterId==null||it.lineId==selectedLineFilterId)&&
       (it.name+" "+it.machineCode+" "+it.machineType+" "+it.location).contains(machineSearch,true)
      }
      LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
       item{
        Row(verticalAlignment=Alignment.CenterVertically){
         Column(Modifier.weight(1f)){
          Text("SHOP-FLOOR ASSETS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp)
          Text("Machinery & equipment",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
          Text(machines.size.toString()+" registered asset(s)",fontSize=12.sp,color=Muted)
         }
         Button(onClick={editingMachine=null;dialog="machine"},shape=RoundedCornerShape(13.dp)){
          Icon(Icons.Default.Add,null)
          Spacer(Modifier.width(4.dp))
          Text("Add asset")
         }
        }
       }
       item{
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
         Metric("Running",machines.count{it.status=="RUNNING"}.toString(),Modifier.weight(1f))
         Metric("Attention",machines.count{it.status in setOf("DOWN","MAINTENANCE")}.toString(),Modifier.weight(1f))
         Metric("Stopped",machines.count{it.status=="STOPPED"}.toString(),Modifier.weight(1f))
        }
       }
       item{
        OutlinedTextField(machineSearch,{machineSearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search asset name, code, type or location")},label={Text("Filter machinery")})
       }
       item{
        Text("FILTER BY PRODUCTION LINE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.2.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
         FilterChip(selected=selectedLineFilterId==null,onClick={selectedLineFilterId=null},label={Text("All lines (${machines.size})",fontSize=10.sp)})
         productionLines.forEach{pl->
          val count=machines.count{it.lineId==pl.id}
          FilterChip(selected=selectedLineFilterId==pl.id,onClick={selectedLineFilterId=if(selectedLineFilterId==pl.id)null else pl.id},label={Text("${pl.lineCode} ($count)",fontSize=10.sp)})
         }
        }
       }
       if(filteredMachines.isEmpty()){
        item{EmptyDashboardState("No machinery matching criteria","Register a machine or clear the line filter.",Icons.Default.Settings)}
       }
       items(filteredMachines,key={it.id}){m->
        val assignedLine=productionLines.firstOrNull{it.id==m.lineId}
        val isRunning=m.status=="RUNNING"
        val statusColor=when(m.status){"RUNNING"->Color(0xFF16804A);"STOPPED"->Color(0xFFB45309);"DOWN"->Color(0xFFB42318);else->Color(0xFF7656D9)}
        Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
         Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
          Row(verticalAlignment=Alignment.CenterVertically){
           Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){
            Icon(Icons.Default.Settings,null,tint=Blue,modifier=Modifier.padding(10.dp))
           }
           Column(Modifier.weight(1f).padding(start=10.dp)){
            Text(m.name,fontWeight=FontWeight.Bold,color=Navy,fontSize=15.sp)
            Text(m.machineCode+" · "+m.machineType,fontSize=11.sp,color=Muted)
            if(m.location.isNotBlank())Text(m.location,fontSize=11.sp,color=Muted)
           }
           Surface(color=statusColor.copy(alpha=0.12f),shape=RoundedCornerShape(30.dp)){
            Text(m.status,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=statusColor)
           }
          }
          Surface(color=Page,shape=RoundedCornerShape(10.dp)){
           Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
             Icon(Icons.Default.PrecisionManufacturing,null,tint=Blue,modifier=Modifier.size(14.dp))
             Text(if(assignedLine!=null)"Line: ${assignedLine.name} (${assignedLine.lineCode})" else "Line: Not assigned to any line",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=if(assignedLine!=null)Navy else Color(0xFF9B5B00))
            }
            TextButton(onClick={assigningMachine=m;dialog="assignLine"},contentPadding=PaddingValues(0.dp)){
             Text("Reassign",fontSize=11.sp,color=Blue)
            }
           }
          }
          Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
           Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Button(onClick={
             scope.launch{
              runCatching{
               require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot change machine status"}
               val next=dao.changeMachineRunningStateSafely(
                machineId=m.id,
                organisationId=session?.organisationId?:"local-org",
                actor=session?.uid?:"local"
               )
               SyncScheduler.runNow(context)
               message="Machine status updated to $next"
              }.onFailure{message=it.message}
             }
            },modifier=Modifier.weight(1f),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=if(isRunning)Color(0xFFB45309) else Color(0xFF16804A))){
             Text(if(isRunning)"Stop" else "Mark running",fontSize=11.sp)
            }
            OutlinedButton(onClick={selectedMachineId=m.id;dialog="logDowntime"},modifier=Modifier.weight(1f),shape=RoundedCornerShape(10.dp)){
             Text("Downtime",fontSize=11.sp)
            }
            OutlinedButton(onClick={selectedMachineId=m.id;dialog="scheduleMaintenance"},modifier=Modifier.weight(1f),shape=RoundedCornerShape(10.dp)){
             Text("Service",fontSize=11.sp)
            }
           }
           Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
            OutlinedButton(onClick={editingMachine=m;dialog="machine"},modifier=Modifier.weight(1f),shape=RoundedCornerShape(10.dp)){
             Icon(Icons.Default.Edit,null,modifier=Modifier.size(15.dp),tint=Blue)
             Spacer(Modifier.width(4.dp))
             Text("Edit",fontSize=11.sp,color=Blue)
            }
            Button(onClick={
             scope.launch{
              runCatching{
               require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot change machine status"}
               val nextStatus=dao.changeMachineActivationSafely(
                machineId=m.id,
                actor=session?.uid?:"local",
                organisationId=session?.organisationId?:"local-org"
               )
               SyncScheduler.runNow(context)
               message="Machine set to $nextStatus"
              }.onFailure{message=it.message}
             }
            },modifier=Modifier.weight(1.2f),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=if(m.status=="DEACTIVATED") Color(0xFF16804A) else Color(0xFF6B7E93))){
             Text(if(m.status=="DEACTIVATED") "Activate" else "Deactivate",fontSize=11.sp)
            }
            IconButton(onClick={deletingMachine=m}){
             Icon(Icons.Default.DeleteOutline,null,tint=Color(0xFFB42318),modifier=Modifier.size(20.dp))
            }
           }
          }
         }
        }
       }
      }
     }
    2->{
      val serviceRecords=machineEvents.filter{it.eventType=="MAINTENANCE"}
      val openService=serviceRecords.filter{it.status=="OPEN"}
      LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
       item{
        Row(verticalAlignment=Alignment.CenterVertically){
         Column(Modifier.weight(1f)){
          Text("EQUIPMENT SERVICE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp)
          Text("Maintenance",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
          Text("Preventive scheduling & service work orders",fontSize=12.sp,color=Muted)
         }
         Button(onClick={selectedMachineId=null;dialog="scheduleMaintenance"},shape=RoundedCornerShape(13.dp)){
          Icon(Icons.Default.Add,null)
          Spacer(Modifier.width(4.dp))
          Text("Schedule")
         }
        }
       }
       item{
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
         Metric("Active service",openService.size.toString(),Modifier.weight(1f))
         Metric("Completed",serviceRecords.count{it.status=="CLOSED"}.toString(),Modifier.weight(1f))
        }
       }
       if(serviceRecords.isEmpty()){
        item{EmptyDashboardState("No maintenance records","Schedule preventive or corrective service for factory equipment.",Icons.Default.Build)}
       }
       items(serviceRecords,key={it.id}){e->
        val durationMinutes=((e.endedAt?:System.currentTimeMillis())-e.startedAt).coerceAtLeast(0L)/60000L
        Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
         Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
          Row(verticalAlignment=Alignment.CenterVertically){
           Column(Modifier.weight(1f)){
            Text(e.machineNameSnapshot,fontWeight=FontWeight.Bold,color=Navy,fontSize=15.sp)
            Text(e.maintenanceType.ifBlank{"Maintenance"}+" · Started "+SimpleDateFormat("dd MMM, HH:mm",Locale.US).format(Date(e.startedAt)),fontSize=11.sp,color=Muted)
           }
           Surface(color=if(e.status=="OPEN")Color(0xFFFFF3DE) else Color(0xFFE8F7F0),shape=RoundedCornerShape(30.dp)){
            Text(e.status,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(e.status=="OPEN")Color(0xFFB45309) else Color(0xFF16804A))
           }
          }
          if(e.reason.isNotBlank())Text("Work scope: "+e.reason,fontSize=12.sp,color=Navy)
          Text("Duration: "+(durationMinutes/60)+"h "+(durationMinutes%60)+"m · Logged by: "+e.performedBy,fontSize=11.sp,color=Blue)
          if(e.completionNotes.isNotBlank())Text("Summary: "+e.completionNotes,fontSize=11.sp,color=Navy)
          if(e.partsUsed.isNotBlank())Text("Parts / materials: "+e.partsUsed,fontSize=11.sp,color=Muted)
          if(e.completedBy.isNotBlank())Text("Completed by: "+e.completedBy,fontSize=10.sp,color=Muted)
          if(e.status=="OPEN"){
           Button(onClick={selectedMachineEventId=e.id;dialog="closeMachineEvent"},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(11.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF16804A))){
            Text("Complete service & restore machine")
           }
          }
         }
        }
       }
      }
     }
    3->{
      val stoppageRecords=machineEvents.filter{it.eventType in setOf("DOWNTIME","MACHINE_STOPPED")}
      val openStoppages=stoppageRecords.filter{it.status=="OPEN"}
      LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
       item{
        Row(verticalAlignment=Alignment.CenterVertically){
         Column(Modifier.weight(1f)){
          Text("STOPPAGE LOG",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp)
          Text("Machine downtime",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
          Text("Stoppages, breakdowns and availability tracking",fontSize=12.sp,color=Muted)
         }
         Button(onClick={selectedMachineId=null;dialog="logDowntime"},shape=RoundedCornerShape(13.dp)){
          Icon(Icons.Default.Add,null)
          Spacer(Modifier.width(4.dp))
          Text("Log stoppage")
         }
        }
       }
       item{
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
         Metric("Active breakdowns",openStoppages.size.toString(),Modifier.weight(1f))
         Metric("7-day stopped",String.format(Locale.US,"%.1f h",stoppedMillis/3600000.0),Modifier.weight(1f))
         Metric("Availability est.",String.format(Locale.US,"%.1f%%",availability),Modifier.weight(1f))
        }
       }
       if(stoppageRecords.isEmpty()){
        item{EmptyDashboardState("No downtime recorded","Equipment is running normally with zero logged stoppages.",Icons.Default.CheckCircle)}
       }
       items(stoppageRecords,key={it.id}){e->
        val durationMinutes=((e.endedAt?:System.currentTimeMillis())-e.startedAt).coerceAtLeast(0L)/60000L
        Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
         Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
          Row(verticalAlignment=Alignment.CenterVertically){
           Column(Modifier.weight(1f)){
            Text(e.machineNameSnapshot,fontWeight=FontWeight.Bold,color=Navy,fontSize=15.sp)
            Text(e.eventType+" · Started "+SimpleDateFormat("dd MMM, HH:mm",Locale.US).format(Date(e.startedAt)),fontSize=11.sp,color=Muted)
           }
           Surface(color=if(e.status=="OPEN")Color(0xFFFFF0F0) else Color(0xFFE8F7F0),shape=RoundedCornerShape(30.dp)){
            Text(e.status,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(e.status=="OPEN")Color(0xFFB42318) else Color(0xFF16804A))
           }
          }
          Text("Reason: "+e.reason.ifBlank{"Unspecified stoppage"},fontSize=12.sp,color=Navy)
          Text("Duration: "+(durationMinutes/60)+"h "+(durationMinutes%60)+"m · Performed by: "+e.performedBy,fontSize=11.sp,color=Blue)
          if(e.completionNotes.isNotBlank())Text("Resolution: "+e.completionNotes,fontSize=11.sp,color=Navy)
          if(e.status=="OPEN"){
           Button(onClick={selectedMachineEventId=e.id;dialog="closeMachineEvent"},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(11.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF16804A))){
            Text("Resolve & restore to RUNNING service")
           }
          }
         }
        }
       }
      }
     }
    }
   }
  }
 PageTab.QUALITY->{
  val inspected=qualityInspections.sumOf{it.inspectedBottles};val failed=qualityInspections.sumOf{it.failedBottles}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("QUALITY CONTROL",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp);Text("Inspections",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Defects, inspection results and rejection trends",fontSize=12.sp,color=Muted)};Button(onClick={dialog="quality"},shape=RoundedCornerShape(13.dp)){Icon(Icons.Default.Add,null);Text(" Inspect")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Inspected",inspected.toString(),Modifier.weight(1f));Metric("Failed",failed.toString(),Modifier.weight(1f));Metric("Failure rate",String.format(Locale.US,"%.1f%%",if(inspected>0)failed*100.0/inspected else 0.0),Modifier.weight(1f))}};item{Text("Inspection sample failures are quality-control findings, not additional production rejects. Link each inspection to a production batch to investigate the same defects without double-counting factory rejection totals.",fontSize=11.sp,color=Muted)}
   item{Text("BATCH QUALITY ANALYSIS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.2.sp);Text("Inspection results by production batch",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Quality sample failures are shown separately from production rejects; they are never added together.",fontSize=11.sp,color=Muted)}
   items(qualityInspections.filter{it.productionEntryId!=null}.groupBy{it.productionEntryId!!}.toList().sortedByDescending{(_,records)->records.maxOfOrNull{it.createdAt}?:0L},key={it.first}){(batchId,records)->
    val batch=production.firstOrNull{it.id==batchId};val sampleInspected=records.sumOf{it.inspectedBottles};val sampleFailed=records.sumOf{it.failedBottles};val batchRejects=batch?.rejectedBottles?:0
    Surface(color=Color.White,shape=RoundedCornerShape(15.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("Batch #$batchId · "+(batch?.productNameSnapshot?:records.first().productNameSnapshot),fontWeight=FontWeight.Bold,color=Navy);Text("Production date: "+(batch?.productionDate?:records.first().inspectionDate),fontSize=11.sp,color=Muted);Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("QC sample failed",sampleFailed.toString(),Modifier.weight(1f));Metric("Production rejects",batchRejects.toString(),Modifier.weight(1f))};Text("QC failure rate: "+String.format(Locale.US,"%.1f%%",if(sampleInspected>0)sampleFailed*100.0/sampleInspected else 0.0)+" · "+records.size+" inspection(s)",fontSize=11.sp,color=Blue);Text("These are different measures. Do not sum them unless the inspected bottles are explicitly reconciled to rejected units.",fontSize=10.sp,color=Muted)}}}
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
   items(plans,key={it.id}){p->val done=production.filter{it.productionDate==p.planDate&&it.productId==p.productId}.sumOf{it.producedBottles};Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(p.planDate+" · "+p.shift,fontSize=11.sp,color=Muted)};Text(p.status,fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue)};Row(verticalAlignment=Alignment.CenterVertically){Text("Target "+p.targetBottles,Modifier.weight(1f),fontSize=12.sp,color=Muted);Text("Actual "+done,fontSize=12.sp,fontWeight=FontWeight.Bold,color=Navy)};LinearProgressIndicator(progress={if(p.targetBottles>0)(done.toFloat()/p.targetBottles).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(7.dp),color=Color(0xFF16804A),trackColor=Page);if(p.status=="PLANNED")Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){if(done==0)TextButton(onClick={editingPlan=p;dialog="editPlan"}){Icon(Icons.Default.Edit,null);Text(" Edit")};TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot update plans"};dao.updateProductionPlanStatus(p.id,"IN_PROGRESS");dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan-start:${p.id}",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_STARTED",payloadJson=JSONObject().put("planId",p.id).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_STARTED",entityType="production_plan",entityId=p.id.toString(),beforeJson="PLANNED",afterJson="IN_PROGRESS",reason="Production plan started",actorId=session?.uid?:"local"));SyncScheduler.runNow(context);message="Plan marked in progress and sync queued"}.onFailure{message=it.message}}}){Text("Start plan")};TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot cancel plans"};require(dao.productionCountForPlan(p.productId,p.planDate)==0){"Production already recorded for this product/date; cancellation blocked"};require(dao.cancelPlannedProduction(p.id)==1){"Only planned items can be cancelled"};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan-cancel:${p.id}",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_CANCELLED",payloadJson=JSONObject().put("planId",p.id).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_CANCELLED",entityType="production_plan",entityId=p.id.toString(),beforeJson="PLANNED",afterJson="CANCELLED",reason="Production plan cancelled before production",actorId=session?.uid?:"local"));SyncScheduler.runNow(context);message="Production plan cancelled"}.onFailure{message=it.message}}}){Text("Cancel")}};if(p.status=="IN_PROGRESS")TextButton(onClick={scope.launch{runCatching{
require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot complete production plans"}
require(p.status=="IN_PROGRESS"){"Only in-progress plans can be completed"}
dao.updateProductionPlanStatus(p.id,"COMPLETED")
dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan-complete:${p.id}",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_COMPLETED",payloadJson=JSONObject().put("planId",p.id).put("productId",p.productId).put("date",p.planDate).toString()))
dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_COMPLETED",entityType="production_plan",entityId=p.id.toString(),beforeJson="IN_PROGRESS",afterJson="COMPLETED",reason="Production plan marked completed",actorId=session?.uid?:"local"))
SyncScheduler.runNow(context)
message="Plan completed and sync queued"
}.onFailure{message=it.message}}}){Text("Mark completed")}}}}
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
   items(orders, key = { it.id }) { o ->
     val lines = orderLines.filter { it.orderId == o.id }
     val ordered = lines.sumOf { it.packsOrdered }
     val fulfilled = lines.sumOf { it.packsDispatched }
     Surface(
      color = Color.White,
      shape = RoundedCornerShape(16.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Border)
     ) {
      Column(
       Modifier.fillMaxWidth().padding(14.dp),
       verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
       Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
         Text(o.orderNumber, fontWeight = FontWeight.ExtraBold, color = Navy)
         Text(o.customerNameSnapshot, fontSize = 12.sp, color = Muted)
         Text("Due " + o.dueDate, fontSize = 11.sp, color = Muted)
        }
        Text(o.status, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue)
       }
       Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Fulfilled $fulfilled / $ordered packs", Modifier.weight(1f), fontSize = 12.sp, color = Navy)
        if (o.status in setOf("OPEN", "PARTIAL") && fulfilled == 0) {
         TextButton(onClick = { editingOrder = o; dialog = "editOrder" }) {
          Icon(Icons.Default.Edit, null)
          Text(" Edit")
         }
        }
        TextButton(onClick = { selectedOrderId = o.id; dialog = "dispatch" }) {
         Icon(Icons.Default.LocalShipping, null)
         Text(" Create dispatch")
        }
        if (o.status in setOf("OPEN", "PARTIAL") && fulfilled == 0) {
         TextButton(onClick = {
          scope.launch {
           runCatching {
            require(roleAllows(session, FactoryAction.MANAGE_ORDERS)) { "Your role cannot cancel sales orders" }
            dao.cancelSalesOrderSafely(o.id)
            dao.enqueueSyncEvent(
             SyncOutboxEntity(
              eventKey = "sales-order-cancel:${o.id}",
              organisationId = session?.organisationId ?: "local-org",
              eventType = "SALES_ORDER_CANCELLED",
              payloadJson = JSONObject().put("orderId", o.id).put("orderNumber", o.orderNumber).toString()
             )
            )
            dao.insertAudit(
             AuditEventEntity(
              eventType = "SALES_ORDER_CANCELLED",
              entityType = "sales_order",
              entityId = o.id.toString(),
              beforeJson = o.status,
              afterJson = "CANCELLED",
              reason = "Sales order cancelled before dispatch",
              actorId = session?.uid ?: "local"
             )
            )
            SyncScheduler.runNow(context)
            message = "Sales order cancelled"
           }.onFailure { message = it.message }
          }
         }) {
          Text("Cancel order")
         }
        }
       }
       if (lines.isNotEmpty()) {
        LinearProgressIndicator(
         progress = { if (ordered > 0) (fulfilled.toFloat() / ordered).coerceIn(0f, 1f) else 0f },
         modifier = Modifier.fillMaxWidth().height(7.dp),
         color = Color(0xFF16804A),
         trackColor = Page
        )
       }
      }
     }
   }
  }
 }
 PageTab.SYNC->{
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CONNECTIVITY",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.3.sp);Text("Offline sync",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Queue state, retries and organisation conflicts",fontSize=12.sp,color=Muted)};Button(onClick={SyncScheduler.runNow(context);message="Sync worker queued"},shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Sync,null);Text(" Retry")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("Pending",syncEvents.count{it.status=="PENDING"}.toString(),Modifier.weight(1f));Metric("Synced",syncEvents.count{it.status=="SYNCED"}.toString(),Modifier.weight(1f));Metric("Review",syncEvents.count{it.status=="NEEDS_REVIEW"}.toString(),Modifier.weight(1f))}}
   item{Text("Events marked NEEDS_REVIEW are intentionally not retried automatically. They need an administrator to verify the organisation assignment or resolve an idempotency conflict.",fontSize=11.sp,color=Color(0xFF9B5B00))}
   if(syncEvents.isEmpty())item{EmptyDashboardState("No sync events","New offline-capable operations will appear here.",Icons.Default.Sync)}
   items(syncEvents,key={it.id}){e->Surface(color=Color.White,shape=RoundedCornerShape(14.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(e.eventType,Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Navy);Text(e.status,fontSize=10.sp,fontWeight=FontWeight.Bold,color=when(e.status){"SYNCED"->Color(0xFF16804A);"NEEDS_REVIEW"->Color(0xFFB45309);else->Blue})};Text("Organisation: "+e.organisationId+" · Attempts: "+e.attempts,fontSize=10.sp,color=Muted);if(e.lastError.isNotBlank())Text(e.lastError,fontSize=11.sp,color=Color(0xFF9B5B00));Text("Key: "+e.eventKey,fontSize=9.sp,color=Muted)}}}
  }
 }
 PageTab.STAFF->{
  val currentRole=session?.role?.let{runCatching{FactoryPermissions.fromClaim(it)}.getOrNull()}
  val roles=FactoryRole.values().toList()
  val actions=FactoryAction.values().toList()
  val canManageStaff=roleAllows(session,FactoryAction.MANAGE_STAFF)
  var showMatrix by remember{mutableStateOf(false)}

  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{
    Row(verticalAlignment=Alignment.CenterVertically){
     Column(Modifier.weight(1f)){
      Text("STAFF & SECURITY",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.4.sp)
      Text("Team & access",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
      Text("Manage staff credentials, permissions and active status",fontSize=12.sp,color=Muted)
     }
     if(canManageStaff){
      Button(
       onClick={editingStaff=null;dialog="staff"},
       shape=RoundedCornerShape(13.dp),
       colors=ButtonDefaults.buttonColors(containerColor=Blue)
      ){
       Icon(Icons.Default.PersonAdd,null,modifier=Modifier.size(18.dp))
       Spacer(Modifier.width(6.dp))
       Text("Create Staff",fontSize=13.sp,fontWeight=FontWeight.Bold)
      }
     }
    }
   }

   item{
    Surface(color=Navy,shape=RoundedCornerShape(18.dp)){
     Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){
        Text("LOGGED IN ACCOUNT",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.sp)
        Text(session?.name?.ifBlank{null}?:session?.role?.uppercase()?:"LOCAL ADMINISTRATOR",fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Color.White)
        Text(session?.email?:if(session!=null)"Organisation: "+session!!.organisationId else "Local Device Mode · Storage active",fontSize=11.sp,color=Color(0xFFD4E2F0))
       }
       Surface(color=Color.White.copy(alpha=0.15f),shape=RoundedCornerShape(20.dp)){
        Text(session?.role?.uppercase()?:"ADMIN",modifier=Modifier.padding(horizontal=10.dp,vertical=4.dp),color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold)
       }
      }
      if(session?.allowedModules?.isNotBlank()==true&&session?.allowedModules!="ALL"){
       Text("Allowed modules: "+session!!.allowedModules.split(",").joinToString(", "){it.trim().lowercase().replaceFirstChar{c->c.uppercase()}},fontSize=11.sp,color=Cyan)
      }
     }
    }
   }

   item{
    Row(verticalAlignment=Alignment.CenterVertically){
     Text("STAFF ACCOUNTS (${staffMembers.size})",modifier=Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Navy)
    }
   }

   if(staffMembers.isEmpty()){
    item{
     EmptyDashboardState(
      "No staff accounts registered",
      "Tap '+ Create Staff' to provision new staff credentials with customized module access.",
      Icons.Default.PeopleOutline
     )
    }
   }

   items(staffMembers,key={it.id}){staff->
    Surface(
     color=Color.White,
     shape=RoundedCornerShape(16.dp),
     border=androidx.compose.foundation.BorderStroke(1.dp,if(staff.active)Border else Color(0xFFFFD1D1))
    ){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Box(
        Modifier.size(38.dp).background(if(staff.active)Color(0xFFE0F2FE) else Color(0xFFFEE2E2),RoundedCornerShape(10.dp)),
        contentAlignment=Alignment.Center
       ){
        Icon(
         if(staff.active)Icons.Default.Person else Icons.Default.PersonOff,
         null,
         tint=if(staff.active)Blue else Color(0xFFB42318),
         modifier=Modifier.size(20.dp)
        )
       }
       Spacer(Modifier.width(10.dp))
       Column(Modifier.weight(1f)){
        Text(staff.name,fontWeight=FontWeight.Bold,fontSize=15.sp,color=Navy)
        Text(staff.email,fontSize=12.sp,color=Muted)
       }
       Surface(
        color=if(staff.active)Color(0xFFE8F7F0) else Color(0xFFFFF0F0),
        shape=RoundedCornerShape(20.dp)
       ){
        Text(
         if(staff.active)"ACTIVE" else "DEACTIVATED",
         modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp),
         fontSize=9.sp,
         fontWeight=FontWeight.Bold,
         color=if(staff.active)Color(0xFF16804A) else Color(0xFFB42318)
        )
       }
      }

      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
       Surface(color=Color(0xFFEEF2F6),shape=RoundedCornerShape(8.dp)){
        Text("Role: "+staff.role.replace("_"," "),modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp),fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=Navy)
       }
      }

      val modulesList = staff.allowedModules.split(",").map{it.trim()}.filter{it.isNotEmpty()}
      if(modulesList.isNotEmpty()&&staff.allowedModules!="ALL"){
       Text("Allowed Access:",fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=Muted)
       Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
        modulesList.take(5).forEach{mod->
         Surface(color=Color(0xFFE0F2FE),shape=RoundedCornerShape(6.dp)){
          Text(mod.lowercase().replaceFirstChar{c->c.uppercase()},modifier=Modifier.padding(horizontal=6.dp,vertical=2.dp),fontSize=9.sp,color=Blue,fontWeight=FontWeight.Bold)
         }
        }
        if(modulesList.size>5){
         Surface(color=Color(0xFFEEF2F6),shape=RoundedCornerShape(6.dp)){
          Text("+${modulesList.size-5} more",modifier=Modifier.padding(horizontal=6.dp,vertical=2.dp),fontSize=9.sp,color=Muted)
         }
        }
       }
      }else{
       Text("Full Access: Admin / Manager standard permissions",fontSize=10.sp,color=Muted)
      }

      var showStaffWork by remember{mutableStateOf(false)}
      val staffWorkEvents = auditEvents.filter{it.actorId.equals(staff.email,true) || it.actorId.equals(staff.uid,true) || it.actorId.equals(staff.name,true)}
      
      Row(
       modifier=Modifier.fillMaxWidth().clickable{showStaffWork=!showStaffWork}.padding(vertical=3.dp),
       verticalAlignment=Alignment.CenterVertically
      ){
       Icon(Icons.Default.HistoryEdu,null,tint=Blue,modifier=Modifier.size(15.dp))
       Spacer(Modifier.width(4.dp))
       Text("Staff Work History (${staffWorkEvents.size} tasks logged)",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Blue,modifier=Modifier.weight(1f))
       Icon(if(showStaffWork)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null,tint=Blue,modifier=Modifier.size(16.dp))
      }
      if(showStaffWork){
       Surface(color=Page,shape=RoundedCornerShape(10.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
        Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
         if(staffWorkEvents.isEmpty()){
          Text("No recorded operations yet for this staff member.",fontSize=11.sp,color=Muted)
         } else {
          staffWorkEvents.take(5).forEach{evt->
           Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
             Text(evt.eventType.replace("_"," "),fontSize=11.sp,fontWeight=FontWeight.Bold,color=Navy)
             Text("${evt.entityType} · ${evt.reason}",fontSize=10.sp,color=Muted)
            }
            Text(SimpleDateFormat("dd MMM, HH:mm",Locale.US).format(Date(evt.createdAt)),fontSize=9.sp,color=Muted)
           }
          }
         }
        }
       }
      }

      if(canManageStaff){
       HorizontalDivider(color=Border)
       Row(
        modifier=Modifier.fillMaxWidth(),
        horizontalArrangement=Arrangement.End,
        verticalAlignment=Alignment.CenterVertically
       ){
        TextButton(onClick={editingStaff=staff;dialog="staff"}){
         Icon(Icons.Default.Edit,null,modifier=Modifier.size(15.dp),tint=Blue)
         Spacer(Modifier.width(4.dp))
         Text("Edit",fontSize=11.sp,color=Blue)
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick={
         scope.launch{
          runCatching{
           dao.setStaffMemberActive(staff.id,!staff.active)
           SyncScheduler.runNow(context)
           message=if(staff.active)"Staff member deactivated" else "Staff member activated"
          }.onFailure{message=it.message}
         }
        }){
         Icon(if(staff.active)Icons.Default.Block else Icons.Default.CheckCircle,null,modifier=Modifier.size(15.dp),tint=if(staff.active)Color(0xFFD97706) else Color(0xFF16804A))
         Spacer(Modifier.width(4.dp))
         Text(if(staff.active)"Deactivate" else "Activate",fontSize=11.sp,color=if(staff.active)Color(0xFFD97706) else Color(0xFF16804A))
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick={
         scope.launch{
          runCatching{
           dao.deleteStaffMember(staff.id)
           SyncScheduler.runNow(context)
           message="Staff member removed"
          }.onFailure{message=it.message}
         }
        }){
         Icon(Icons.Default.DeleteOutline,null,modifier=Modifier.size(15.dp),tint=Color(0xFFB42318))
         Spacer(Modifier.width(4.dp))
         Text("Remove",fontSize=11.sp,color=Color(0xFFB42318))
        }
       }
      }
     }
    }
   }

   item{
    Row(
     modifier=Modifier.fillMaxWidth().clickable{showMatrix=!showMatrix}.padding(vertical=6.dp),
     verticalAlignment=Alignment.CenterVertically
    ){
     Text("Role Capability Reference",modifier=Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Navy)
     Icon(if(showMatrix)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null,tint=Muted)
    }
   }

   if(showMatrix){
    items(roles){role->
     Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(role==currentRole)Blue else Border)){
      Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
       Row(verticalAlignment=Alignment.CenterVertically){
        Text(role.name.replace("_"," "),Modifier.weight(1f),fontWeight=FontWeight.Bold,color=Navy)
        if(role==currentRole)Text("CURRENT ROLE",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Blue)
       }
       val permitted=actions.filter{FactoryPermissions.allows(role,it)}
       Text("Allowed actions",fontSize=10.sp,color=Muted)
       Text(permitted.joinToString(" · "){it.name.lowercase().replace("_"," ")},fontSize=11.sp,color=Navy)
       Text("Restricted: "+actions.filter{!FactoryPermissions.allows(role,it)}.joinToString(" · "){it.name.lowercase().replace("_"," ")},fontSize=10.sp,color=Color(0xFF9B5B00))
      }
     }
    }
   }
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
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("FACTORY INTELLIGENCE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.5.sp);Text("Reports",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Performance from saved factory records",fontSize=12.sp,color=Muted)};IconButton(enabled=roleAllows(session,FactoryAction.EXPORT_REPORTS),onClick={if(roleAllows(session,FactoryAction.EXPORT_REPORTS))exportLauncher.launch("tck-factoryflow-report-"+date+".csv")}){Icon(Icons.Default.FileDownload,null,tint=Blue)}}}
   item{Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("REPORT PERIOD",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.1.sp);OutlinedTextField(reportFrom,{reportFrom=it},label={Text("From date · yyyy-MM-dd")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp));OutlinedTextField(reportTo,{reportTo=it},label={Text("To date · yyyy-MM-dd")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp));Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton(onClick={reportFrom=date;reportTo=date}){Text("Today")};TextButton(onClick={reportFrom=java.time.LocalDate.parse(date).withDayOfMonth(1).toString();reportTo=date}){Text("This month")};TextButton(onClick={reportTo=date;reportFrom=java.time.LocalDate.parse(date).minusDays(6).toString()}){Text("7 days")}};Button(enabled=roleAllows(session,FactoryAction.EXPORT_REPORTS),onClick={if(roleAllows(session,FactoryAction.EXPORT_REPORTS))exportLauncher.launch("tck-factoryflow-"+reportFrom+"-to-"+reportTo+".csv")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.FileDownload,null);Spacer(Modifier.width(6.dp));Text("Export selected period")}}}}
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
   item{
    val lineRows=productionLines.map{line->
     val lineBottles=production.filter{it.productionDate in reportFrom..reportTo&&it.lineId==line.id}.sumOf{it.producedBottles}
     line to lineBottles
    }
    val totalLineBottles=lineRows.sumOf{it.second}.coerceAtLeast(1)
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){
        Text("Production by line",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)
        Text("Bottles produced per operational line · selected period",fontSize=11.sp,color=Muted)
       }
       Icon(Icons.Default.PrecisionManufacturing,null,tint=Blue)
      }
      if(lineRows.all{it.second==0}){
       Text("No line-tagged production recorded in this period.",fontSize=12.sp,color=Muted)
      } else {
       lineRows.forEach{(line,bottles)->
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
         Row(verticalAlignment=Alignment.CenterVertically){
          Text(line.name+" ("+line.lineCode+")",Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
          Text(String.format(Locale.US,"%,d bottles",bottles),fontSize=12.sp,fontWeight=FontWeight.Bold,color=Blue)
         }
         LinearProgressIndicator(progress={bottles.toFloat()/totalLineBottles.toFloat()},modifier=Modifier.fillMaxWidth().height(6.dp),color=Blue,trackColor=Page,strokeCap=androidx.compose.ui.graphics.StrokeCap.Round)
        }
       }
      }
     }
    }
   }
   item{
    val periodEvents=machineEvents.filter{
     val eventDate=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(it.startedAt))
     eventDate in reportFrom..reportTo
    }
    val downtimeByMachine=machines.map{m->
     val events=periodEvents.filter{it.machineId==m.id}
     val totalMinutes=events.sumOf{((it.endedAt?:System.currentTimeMillis())-it.startedAt).coerceAtLeast(0L)/60000L}
     Triple(m,events.size,totalMinutes)
    }.filter{it.third>0}.sortedByDescending{it.third}
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){
        Text("Machine downtime & stoppage",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Navy)
        Text("Total stoppage duration per machine · selected period",fontSize=11.sp,color=Muted)
       }
       Icon(Icons.Default.HourglassBottom,null,tint=Color(0xFFB45309))
      }
      if(downtimeByMachine.isEmpty()){
       Text("No machine stoppage or maintenance events recorded in this period.",fontSize=12.sp,color=Muted)
      } else {
       downtimeByMachine.forEach{(m,count,minutes)->
        Row(verticalAlignment=Alignment.CenterVertically){
         Column(Modifier.weight(1f)){
          Text(m.name,fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp)
          Text(m.machineCode+" · "+count+" event(s)",fontSize=10.sp,color=Muted)
         }
         Text(String.format(Locale.US,"%.1f hours",minutes/60.0),fontWeight=FontWeight.Bold,color=Color(0xFFB45309),fontSize=13.sp)
        }
       }
      }
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
 FullSlideMenuScreen(
  isOpen = showTools,
  onClose = { showTools = false },
  currentTab = tab,
  onSelectTab = { setTab(it) },
  session = session,
  onOpenRoleSwitcher = { dialog = "role_switcher" },
  onQuickAction = { action -> dialog = action },
  onExportDb = { databaseBackupLauncher.launch("tck-factoryflow-backup-${System.currentTimeMillis()}.sqlite3") },
  onRestoreDb = { databaseRestoreLauncher.launch(arrayOf("application/vnd.sqlite3", "application/octet-stream", "*/*")) },
  onExportJson = { backupLauncher.launch("tck-factoryflow-backup-${System.currentTimeMillis()}.json") },
  onExportCsv = { if(roleAllows(session,FactoryAction.EXPORT_REPORTS)) exportLauncher.launch("factory-report-${System.currentTimeMillis()}.csv") },
  onSyncNow = { SyncScheduler.runNow(context); message = "Cloud sync triggered" },
  firebaseConfigured = FirebaseApp.getApps(context).isNotEmpty(),
  onSignOut = {
   FactoryAuthRepository(context).signOut()
   session = null
   showTools = false
   message = "Signed out"
  }
 )
 if(dialog=="reverse"&&reversalBatchId!=null)ReverseDispatchForm({dialog="";reversalBatchId=null}){reason->scope.launch{runCatching{require(roleAllows(session,FactoryAction.REVERSE_DISPATCH)){"Your role cannot reverse dispatches"};if(session?.isCloud==true){val b=dispatchBatches.firstOrNull{it.id==reversalBatchId};if(b!=null){CloudInventoryRepository(context).reverseDispatch(session!!,b.idempotencyKey,reason)}};dao.reverseDispatch(reversalBatchId!!,reason,session?.uid?:"local",session?.organisationId?:"local-org");SyncScheduler.runNow(context);"Dispatch reversed"+if(session?.isCloud==true)" · server confirmed" else ""}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalBatchId=null}
 if(dialog=="reverseProduction"&&reversalProductionId!=null)ReverseProductionForm({dialog="";reversalProductionId=null}){reason->scope.launch{runCatching{
 require(roleAllows(session,FactoryAction.CORRECT_RECORDS)){"Your role cannot reverse production records"}
 if(session?.isCloud==true){
  val entry=production.firstOrNull{it.id==reversalProductionId}
  require(entry!=null&&entry.cloudOperationKey.isNotBlank()){"Cloud production reference missing"}
  val reversed=CloudInventoryRepository(context).reverseProduction(session!!,entry.cloudOperationKey,reason)
  require(reversed){"Production reversal was not confirmed by server"}
 }
 val corrected=dao.correctProduction(reversalProductionId!!,reason,session?.uid?:"local",session?.organisationId?:"local-org")
 require(corrected){"Production record already reversed or unavailable"}
 SyncScheduler.runNow(context)
 "Production entry reversed"+if(session?.isCloud==true)" · server confirmed" else ""
}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalProductionId=null}

 if(dialog=="stockAdjustment"&&session?.isCloud==true){
  StockAdjustmentForm(active,stockAdjustmentProduct,{dialog=""}){product,qty,unit,type,reason->
   scope.launch{
    runCatching{
     val cloudSession=session?:error("Cloud session unavailable")
     require(roleAllows(cloudSession,FactoryAction.ADJUST_STOCK)){"Your role cannot adjust stock"}
     val result=CloudInventoryRepository(context).adjustStock(cloudSession,product.productCode,qty,unit,type,reason)
     require(result.optBoolean("adjusted")||result.optBoolean("duplicate")){"Server did not confirm stock adjustment"}
     cloudStockSnapshot=CloudInventoryRepository(context).fetchStock(cloudSession)
     SyncScheduler.runNow(context)
     "Stock ${type.lowercase()} confirmed · ${product.name} · $qty $unit"
    }.onSuccess{message=it}.onFailure{message=it.message}
   }
   dialog=""
  }
 }
 if(dialog=="reversePacking"&&reversalPackingId!=null)ReversePackingForm({dialog="";reversalPackingId=null}){reason->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CORRECT_RECORDS)){"Your role cannot reverse packing records"};if(session?.isCloud==true){val pk=packing.firstOrNull{it.id==reversalPackingId};if(pk!=null&&pk.cloudOperationKey.isNotBlank()){CloudInventoryRepository(context).reversePacking(session!!,pk.cloudOperationKey,reason)}};dao.reversePacking(reversalPackingId!!,reason,session?.uid?:"local",session?.organisationId?:"local-org");SyncScheduler.runNow(context);"Packing entry reversed"+if(session?.isCloud==true)" · server confirmed" else ""}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalPackingId=null}

  if(dialog=="line")LineForm(editingLine,{dialog="";editingLine=null}){code,name,desc,cap,unit,status->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot manage production lines"};val existing=editingLine;val lineEntity=ProductionLineEntity(id=existing?.id?:0,lineCode=code,name=name,description=desc,targetCapacity=cap,capacityUnit=unit,status=status);if(session?.isCloud==true){CloudInventoryRepository(context).upsertProductionLine(session!!,lineEntity)};if(existing==null){val id=dao.insertProductionLine(lineEntity);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="line-register:$id",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_LINE_REGISTERED",payloadJson=JSONObject().put("lineId",id).put("code",code).put("name",name).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_LINE_REGISTERED",entityType="production_line",entityId=code,afterJson=name,reason="Production line created",actorId=session?.uid?:"local"));message="Production line $name registered"}else{check(dao.updateProductionLine(existing.id,name,desc,cap,unit,status)==1){"Production line update failed"};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="line-update:${existing.id}",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_LINE_UPDATED",payloadJson=JSONObject().put("lineId",existing.id).put("code",code).put("name",name).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_LINE_UPDATED",entityType="production_line",entityId=existing.id.toString(),beforeJson=existing.name,afterJson=name,reason="Line configuration updated",actorId=session?.uid?:"local"));message="Production line $name updated"}}.onFailure{message=it.message}};dialog="";editingLine=null}
  if(dialog=="assignLine"&&assigningMachine!=null){val machine=assigningMachine!!;AssignMachineLineForm(machine,productionLines,{dialog="";assigningMachine=null}){selectedLineId->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot reassign machines"};val lineCode=productionLines.firstOrNull{it.id==selectedLineId}?.lineCode?:"";if(session?.isCloud==true){CloudInventoryRepository(context).assignMachineLine(session!!,machine.machineCode,lineCode)};check(dao.assignMachineToLine(machine.id,selectedLineId)==1){"Machine line assignment failed"};val lineName=productionLines.firstOrNull{it.id==selectedLineId}?.name?:"Unassigned";dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-assign:${machine.id}:$selectedLineId",organisationId=session?.organisationId?:"local-org",eventType="MACHINE_LINE_ASSIGNED",payloadJson=JSONObject().put("machineId",machine.id).put("lineId",selectedLineId).toString()));dao.insertAudit(AuditEventEntity(eventType="MACHINE_LINE_ASSIGNED",entityType="machine",entityId=machine.id.toString(),beforeJson=machine.lineId?.toString()?:"none",afterJson=selectedLineId?.toString()?:"none",reason="Reassigned to $lineName",actorId=session?.uid?:"local"));message="${machine.name} assigned to $lineName"}.onFailure{message=it.message}};dialog="";assigningMachine=null}}
  if(dialog=="machine")MachineForm(editingMachine,productionLines,{dialog="";editingMachine=null}){code,name,type,location,notes,lineId->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot manage machines"};val existing=editingMachine;val lineCode=productionLines.firstOrNull{it.id==lineId}?.lineCode?:"";val machineEntity=MachineEntity(id=existing?.id?:0,machineCode=code,name=name,machineType=type,location=location,notes=notes,lineId=lineId);if(session?.isCloud==true){CloudInventoryRepository(context).upsertMachine(session!!,machineEntity,lineCode)};if(existing==null){val machineId=dao.insertMachine(machineEntity);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-register:$machineId",organisationId=session?.organisationId?:"local-org",eventType="MACHINE_REGISTERED",payloadJson=JSONObject().put("machineId",machineId).put("code",code).put("name",name).put("type",type).put("lineId",lineId).toString()));dao.insertAudit(AuditEventEntity(eventType="MACHINE_REGISTERED",entityType="machine",entityId=code,afterJson=name,reason="Machine registered",actorId=session?.uid?:"local"));message="Machine $name registered"}else{check(dao.updateMachineWithLine(existing.id,name,type,location,notes,lineId)==1){"Machine update failed"};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-update:${existing.id}",organisationId=session?.organisationId?:"local-org",eventType="MACHINE_UPDATED",payloadJson=JSONObject().put("machineId",existing.id).put("name",name).put("lineId",lineId).toString()));dao.insertAudit(AuditEventEntity(eventType="MACHINE_UPDATED",entityType="machine",entityId=existing.id.toString(),beforeJson=existing.name,afterJson=name,reason="Machine details updated",actorId=session?.uid?:"local"));message="Machine $name updated"}}.onFailure{message=it.message}};dialog="";editingMachine=null}
  if(dialog=="scheduleMaintenance")ScheduleMaintenanceForm(machines,selectedMachineId,{dialog="";selectedMachineId=null}){machine,kind,reason,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot schedule maintenance"};val eventId=dao.createMachineEventSafely(machine.id,"MAINTENANCE",reason,kind,session?.uid?:"local",session?.organisationId?:"local-org");if(session?.isCloud==true){CloudInventoryRepository(context).createMachineEvent(session!!,MachineEventEntity(id=eventId,machineId=machine.id,machineNameSnapshot=machine.name,eventType="MAINTENANCE",startedAt=System.currentTimeMillis(),reason=reason,maintenanceType=kind),machine.machineCode)};message="Maintenance scheduled for ${machine.name} · Event #$eventId"}.onFailure{message=it.message}};dialog="";selectedMachineId=null}
  if(dialog=="logDowntime")LogDowntimeForm(machines,selectedMachineId,{dialog="";selectedMachineId=null}){machine,reason,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot log downtime"};val eventId=dao.createMachineEventSafely(machine.id,"DOWNTIME",reason,"Breakdown",session?.uid?:"local",session?.organisationId?:"local-org");if(session?.isCloud==true){CloudInventoryRepository(context).createMachineEvent(session!!,MachineEventEntity(id=eventId,machineId=machine.id,machineNameSnapshot=machine.name,eventType="DOWNTIME",startedAt=System.currentTimeMillis(),reason=reason,maintenanceType="Breakdown"),machine.machineCode)};message="Downtime logged for ${machine.name} · Event #$eventId"}.onFailure{message=it.message}};dialog="";selectedMachineId=null}
  if(dialog=="closeMachineEvent"&&selectedMachineEventId!=null){val event=machineEvents.firstOrNull{it.id==selectedMachineEventId};if(event!=null)MachineEventCloseForm(event,{dialog="";selectedMachineEventId=null}){completion,parts->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot close machine events"};val recovered=dao.closeMachineEventSafely(event.id,completion,parts,session?.uid?:"local",session?.organisationId?:"local-org",session?.uid?:"local");if(session?.isCloud==true){CloudInventoryRepository(context).closeMachineEvent(session!!,"me-${event.id}",completion,parts,if(recovered)"RUNNING" else "STOPPED")};message=if(recovered)"Machine event closed · Machine recovered to RUNNING" else "Machine event closed"}.onFailure{message=it.message}};dialog="";selectedMachineEventId=null}}
  if(dialog=="quality")QualityForm(active,production,{dialog=""}){product,productionEntry,d,i,p,failed,category,severity,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_QUALITY)){"Your role cannot create quality inspections"};require(i>0&&p in 0..i);val qi=QualityInspectionEntity(productionEntryId=productionEntry?.id,productId=product.id,productNameSnapshot=product.name,inspectionDate=d,inspectedBottles=i,passedBottles=p,failedBottles=failed,defectCategory=category,severity=severity,notes=notes,inspectorId=session?.uid?:"local");val inspectionId=dao.insertQualityInspection(qi);if(session?.isCloud==true){CloudInventoryRepository(context).postQualityInspection(session!!,qi.copy(id=inspectionId))};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="quality-inspection:"+inspectionId,organisationId=session?.organisationId?:"local-org",eventType="QUALITY_INSPECTION_SAVED",payloadJson=JSONObject().put("inspectionId",inspectionId).put("productId",product.id).put("inspected",i).put("passed",p).put("failed",failed).put("defect",category).put("severity",severity).toString()));dao.insertAudit(AuditEventEntity(eventType="QUALITY_INSPECTION_SAVED",entityType="quality",entityId=product.id.toString(),afterJson="inspection saved",reason="Quality inspection recorded",actorId=session?.uid?:"local"));message="Quality inspection saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="editPlan"&&editingPlan!=null){
  val existing=editingPlan!!
  ProductionPlanForm(active,machines,{dialog="";editingPlan=null},{product,d,shift,target,machineId,notes->
   scope.launch{runCatching{
    require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot edit production plans"}
    require(existing.status=="PLANNED"){"Only planned production can be edited"}
    require(dao.productionCountForPlan(existing.productId,existing.planDate)==0){"Production already recorded; editing blocked"}
    require(dao.updatePlannedProductionSafely(existing.id,d,shift,product.id,product.name,target,machineId,notes)==1){"Plan changed or is no longer editable"}
    if(session?.isCloud==true){
      CloudInventoryRepository(context).editProductionPlan(session!!,"plan-${existing.id}",shift,target,machineId,null,notes)
    }
    dao.enqueueSyncEvent(SyncOutboxEntity(
     eventKey="production-plan-edit:${existing.id}:${System.currentTimeMillis()}",
     organisationId=session?.organisationId?:"local-org",
     eventType="PRODUCTION_PLAN_UPDATED",
     payloadJson=JSONObject().put("planId",existing.id).put("date",d).put("shift",shift)
      .put("productId",product.id).put("productName",product.name).put("target",target)
      .put("machineId",machineId).put("notes",notes).toString()
    ))
    dao.insertAudit(AuditEventEntity(
     eventType="PRODUCTION_PLAN_UPDATED",entityType="production_plan",
     entityId=existing.id.toString(),
     beforeJson=JSONObject().put("date",existing.planDate).put("shift",existing.shift)
      .put("productId",existing.productId).put("target",existing.targetBottles).toString(),
     afterJson=JSONObject().put("date",d).put("shift",shift)
      .put("productId",product.id).put("target",target).toString(),
     reason="Planned production details edited",actorId=session?.uid?:"local"
    ))
    SyncScheduler.runNow(context)
    message="Production plan updated"
   }.onFailure{message=it.message}
  }
  dialog="";editingPlan=null
 },initial=existing)
 }
 if(dialog=="plan")ProductionPlanForm(products=active,machines=machines,close={dialog=""},save={product,d,shift,target,machineId,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot create production plans"};val plan=ProductionPlanEntity(planDate=d,shift=shift,productId=product.id,productNameSnapshot=product.name,targetBottles=target,assignedMachineId=machineId,notes=notes,createdBy=session?.uid?:"local");val planId=dao.insertProductionPlan(plan);if(session?.isCloud==true){CloudInventoryRepository(context).createProductionPlan(session!!,plan.copy(id=planId))};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan:"+planId,organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_CREATED",payloadJson=JSONObject().put("planId",planId).put("productId",product.id).put("date",d).put("shift",shift).put("target",target).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_CREATED",entityType="product",entityId=product.id.toString(),afterJson="plan saved",reason="Production plan created",actorId=session?.uid?:"local"));message="Production plan saved"}.onFailure{message=it.message}};dialog=""})
 if(dialog=="editOrder"&&editingOrder!=null){
  val existing=editingOrder!!
  val originalLines=orderLines.filter{it.orderId==existing.id}
  SalesOrderForm(customers,active,{dialog="";editingOrder=null},{customer,number,d,due,lines,notes->
   scope.launch{runCatching{
    require(roleAllows(session,FactoryAction.MANAGE_ORDERS)){"Your role cannot edit sales orders"}
    require(lines.isNotEmpty()){"Add at least one product"}
    require(existing.status in setOf("OPEN","PARTIAL")){"Sales order is no longer editable"}
    val currentLines=dao.salesOrderLinesForOrder(existing.id)
    require(currentLines.none{it.packsDispatched>0}){"Order already has fulfilment; editing blocked"}
    val proposed=lines.map{(product,packs)->
     SalesOrderLineEntity(orderId=existing.id,productId=product.id,
      productNameSnapshot=product.name,packsOrdered=packs,
      packSizeSnapshot=product.bottlesPerPack)
    }
    require(dao.editSalesOrderSafely(existing.id,number,customer.id,customer.name,d,due,notes,proposed)){
     "Sales order changed or is no longer editable"
    }
    if(session?.isCloud==true){
      CloudInventoryRepository(context).editSalesOrder(session!!,number,due,notes,proposed)
    }
    val savedLines=dao.salesOrderLinesForOrder(existing.id)
    dao.enqueueSyncEvent(SyncOutboxEntity(
     eventKey="sales-order-edit:${existing.id}:${System.currentTimeMillis()}",
     organisationId=session?.organisationId?:"local-org",
     eventType="SALES_ORDER_UPDATED",
     payloadJson=JSONObject().put("orderId",existing.id).put("orderNumber",number)
      .put("customerId",customer.id).put("customerName",customer.name)
      .put("orderDate",d).put("dueDate",due).put("notes",notes)
      .put("lines",JSONArray().apply{savedLines.forEach{line->
       put(JSONObject().put("productId",line.productId)
        .put("productName",line.productNameSnapshot)
        .put("packs",line.packsOrdered).put("packSize",line.packSizeSnapshot))
      }}).toString()
    ))
    dao.insertAudit(AuditEventEntity(
     eventType="SALES_ORDER_UPDATED",entityType="sales_order",
     entityId=existing.id.toString(),
     beforeJson=JSONObject().put("orderNumber",existing.orderNumber)
      .put("customer",existing.customerNameSnapshot).put("dueDate",existing.dueDate).toString(),
     afterJson=JSONObject().put("orderNumber",number)
      .put("customer",customer.name).put("dueDate",due).toString(),
     reason="Sales order header and lines edited before dispatch",
     actorId=session?.uid?:"local"
    ))
    SyncScheduler.runNow(context)
    message="Sales order updated · ${savedLines.size} product line(s)"
   }.onFailure{message=it.message}
  }
  dialog="";editingOrder=null
 },initial=existing,initialLines=originalLines)
 }
 if(dialog=="order")SalesOrderForm(customers=customers,products=active,close={dialog=""},save={customer,number,d,due,lines,notes->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_ORDERS)){"Your role cannot create sales orders"};require(lines.isNotEmpty()){"Add at least one product"};val id=dao.insertSalesOrder(SalesOrderEntity(orderNumber=number,customerId=customer.id,customerNameSnapshot=customer.name,orderDate=d,dueDate=due,notes=notes,createdBy=session?.uid?:"local"));val lineEntities=lines.map{pair->val product=pair.first;val packs=pair.second;SalesOrderLineEntity(orderId=id,productId=product.id,productNameSnapshot=product.name,packsOrdered=packs,packSizeSnapshot=product.bottlesPerPack)};dao.insertSalesOrderLines(lineEntities);if(session?.isCloud==true){runCatching{CloudInventoryRepository(context).createSalesOrder(session!!,SalesOrderEntity(id=id,orderNumber=number,customerId=customer.id,customerNameSnapshot=customer.name,orderDate=d,dueDate=due,notes=notes,createdBy=session?.uid?:"local"),lineEntities)}.getOrElse{error("Order saved locally, but cloud sync failed: "+(it.message?:"unknown error"))}};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="sales-order:"+id,organisationId=session?.organisationId?:"local-org",eventType="SALES_ORDER_CREATED",payloadJson=JSONObject().put("orderId",id).put("orderNumber",number).put("customerId",customer.id).put("lines",JSONArray().apply{lines.forEach{pair->val product=pair.first;val packs=pair.second;put(JSONObject().put("productId",product.id).put("productName",product.name).put("packs",packs))}}).toString()));dao.insertAudit(AuditEventEntity(eventType="SALES_ORDER_CREATED",entityType="sales_order",entityId=id.toString(),afterJson="order created with ${lines.size} lines",reason="Sales order created",actorId=session?.uid?:"local"));message="Sales order created · ${lines.size} product line(s)"+if(session?.isCloud==true)" · cloud synced" else ""}.onFailure{message=it.message}};dialog=""})
  // DISABLED: Fulfilment must happen through actual dispatch posting.
  if(dialog=="login"||dialog=="role_switcher")StaffAccountDialog(onDismiss={dialog=""},firebaseConfigured=isServerOnline(context)&&FirebaseApp.getApps(context).isNotEmpty(),onFirebaseSignIn={email,password->if(!isServerOnline(context)){message="Cloud server not reachable. Check your connection."}else scope.launch{runCatching{FactoryAuthRepository(context).signIn(email,password)}.onSuccess{session=it;message="Signed in as "+it.role+" · "+it.organisationId;dialog=""}.onFailure{message=it.message?: "Sign-in failed."}}},onSyncNow={SyncScheduler.runNow(context);message="Cloud sync triggered"},onSignOut={FactoryAuthRepository(context).signOut();session=null;dialog="";message="Signed out"})
  if(dialog=="product")ProductForm(editingProduct,{dialog="";editingProduct=null}){n,s,c,p,icon,img->val existing=editingProduct;scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot manage products"};val localId=if(existing==null)dao.insertProduct(ProductEntity(name=n,sizeLabel=s,colour=c,bottlesPerPack=p,iconName=icon,imageUri=img)) else {dao.updateProductWithHistory(existing.id,n,s,c,p,existing.active,session?.uid?:"local","Product configuration edited",session?.organisationId?:"local-org",iconName=icon,imageUri=img);existing.id};val saved=dao.productById(localId)?:error("Product was saved but could not be reloaded");if(session?.isCloud==true){runCatching{CloudInventoryRepository(context).upsertProduct(session!!,saved)}.getOrElse{error("Product saved locally, but cloud catalog sync failed: "+(it.message?:"unknown error"))}};SyncScheduler.runNow(context);message=if(p>0)"${n.trim()} saved · $p bottles per pack"+if(session?.isCloud==true)" · cloud catalog synced" else "" else "${n.trim()} saved"+if(session?.isCloud==true)" · cloud catalog synced" else ""}.onFailure{message=it.message}};dialog="";editingProduct=null}
   if(dialog=="production")ProductionForm(active,productionLines,machines,{dialog=""}){p,d,total,good,rejected,lineId,lineName,machineId,machineName,remarks->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot post production"};requireCloudSession(session);ProductionQuantities(total,good,rejected).validate();require(p!=null){"Choose a product"};val entry=ProductionEntryEntity(productId=p.id,productNameSnapshot=p.name,productionDate=d,producedBottles=total,goodBottles=good,rejectedBottles=rejected,machineName=machineName.ifBlank{"Machine 1"},remarks=remarks,lineId=lineId,lineNameSnapshot=lineName,machineId=machineId);val cloudSession=requireCloudSession(session);val cloudRepo=CloudInventoryRepository(context);val cloudKey=cloudRepo.postProduction(cloudSession,p,entry);val id=dao.recordCloudProduction(entry.copy(cloudOperationKey=cloudKey),cloudSession.uid);cloudRepo.acknowledgeProduction(entry,p,cloudSession);SyncScheduler.runNow(context);message="Production run #$id saved · $total bottles · server confirmed"}.onFailure{message=it.message}};dialog=""}
   if(dialog=="packing")PackingForm(active,dao,{dialog=""},cloudAvailableBottles={product->if(session?.isCloud==true)cloudStockSnapshot?.items?.firstOrNull{it.productId==product?.productCode}?.unpackedGoodBottles else null}){p,d,n->scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PACKING)){"Your role cannot post packing"};require(p!=null){"Choose a product"};require(p.bottlesPerPack>0){"Pack size is not configured for ${p.name}. Please set bottles per pack in Product Master first."};requireCloudSession(session);val calc=PackCalculation.calculate(n,p.bottlesPerPack);val entry=PackingEntryEntity(productId=p.id,productNameSnapshot=p.name,packingDate=d,bottlesConsumed=n,completePacks=calc.completePacks,looseBottles=calc.looseBottles);val cloudSession=requireCloudSession(session);val cloudRepo=CloudInventoryRepository(context);val result=cloudRepo.postPacking(cloudSession,p,entry);if(result.bottlesPerPack!=p.bottlesPerPack)dao.updateProductDetails(p.id,p.name,p.sizeLabel,p.colour,result.bottlesPerPack,p.active,p.iconName,p.imageUri);val confirmed=entry.copy(completePacks=result.completePacks,looseBottles=result.looseBottles,cloudOperationKey=result.idempotencyKey);dao.recordCloudPacking(confirmed,cloudSession.uid);cloudRepo.acknowledgePacking(entry,p,cloudSession);SyncScheduler.runNow(context);"Saved "+result.completePacks+" packs and "+result.looseBottles+" loose bottles · server confirmed"}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
   if(dialog=="dispatch")DispatchBatchForm(active,customers,openOrders,orderLines,selectedOrderId,{dialog="";selectedOrderId=null}){customer,customerName,d,reference,remarks,lines,key,orderId->scope.launch{runCatching{require(roleAllows(session,FactoryAction.POST_DISPATCH)){"Your role cannot post dispatches"};val cloudSession=requireCloudSession(session);val cleanRef=reference.ifBlank{"DSP-"+System.currentTimeMillis()};val batch=DispatchBatchEntity(idempotencyKey=key,dispatchReference=cleanRef,dispatchDate=d,customerId=customer?.id,customerNameSnapshot=customerName,remarks=remarks,createdBy=cloudSession.uid,salesOrderId=orderId);val cloudRepo=CloudInventoryRepository(context);val cloudLines=lines.map{l->val prod=active.firstOrNull{it.id==l.productId};mapOf<String,Any>("productId" to (prod?.productCode?:l.productId.toString()),"packs" to l.packs,"looseBottles" to l.looseBottles)};cloudRepo.postDispatch(cloudSession,key,cleanRef,d,customer?.id?.toString()?:"walk-in",customerName,cloudLines,remarks);if(orderId!=null)dao.postSalesOrderDispatch(batch,lines,cloudSession.organisationId,cloudSession.uid) else dao.postMultiLineDispatch(batch,lines,cloudSession.organisationId,cloudSession.uid);SyncScheduler.runNow(context);"Dispatch saved · server confirmed"+if(orderId!=null)" and order fulfilment reconciled" else ""}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";selectedOrderId=null}
 if(dialog=="customer")CustomerForm(null,{dialog=""}){name,phone,address,gst->scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_CUSTOMERS)){"Your role cannot manage customers"};val customerId=dao.insertCustomer(CustomerEntity(name=name,phone=phone,address=address,gstNumber=gst));dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="customer-create:$customerId",organisationId=session?.organisationId?:"local-org",eventType="CUSTOMER_CREATED",payloadJson=JSONObject().put("customerId",customerId).put("name",name).put("phone",phone).put("address",address).put("gstNumber",gst).toString()));dao.insertAudit(AuditEventEntity(eventType="CUSTOMER_CREATED",entityType="customer",entityId=customerId.toString(),afterJson=JSONObject().put("name",name).put("phone",phone).toString(),reason="Customer created",actorId=session?.uid?:"local"));SyncScheduler.runNow(context)}.onSuccess{message="Customer saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="customerEdit")CustomerForm(editingCustomer,{dialog="";editingCustomer=null}){name,phone,address,gst->val existing=editingCustomer;scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_CUSTOMERS)){"Your role cannot manage customers"};require(existing!=null);val oldCustomer=existing!!;dao.updateCustomer(oldCustomer.id,name,phone,address,gst,oldCustomer.active);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="customer-update:${oldCustomer.id}:${System.currentTimeMillis()}",organisationId=session?.organisationId?:"local-org",eventType="CUSTOMER_UPDATED",payloadJson=JSONObject().put("customerId",oldCustomer.id).put("name",name).put("phone",phone).put("address",address).put("gstNumber",gst).put("active",oldCustomer.active).toString()));dao.insertAudit(AuditEventEntity(eventType="CUSTOMER_UPDATED",entityType="customer",entityId=oldCustomer.id.toString(),beforeJson=JSONObject().put("name",oldCustomer.name).put("phone",oldCustomer.phone).toString(),afterJson=JSONObject().put("name",name).put("phone",phone).toString(),reason="Customer details edited",actorId=session?.uid?:"local"));SyncScheduler.runNow(context)}.onSuccess{message="Customer details updated"}.onFailure{message=it.message}};dialog="";editingCustomer=null}
  if(dialog=="meter"){
   val pending=selectedMeterReading?:meters.firstOrNull{it.status=="PENDING"}
   MeterForm(
    initialMode=meterDialogMode,
    initialReading=pending,
    meters=meters,
    close={dialog="";selectedMeterReading=null},
    onSaveMorning={d,m,s,r->
     scope.launch{
      runCatching{
       require(roleAllows(session,FactoryAction.MANAGE_ELECTRICITY)){"Your role cannot manage electricity readings"}
       val existing=dao.meterReadingByDate(m,d)
       val toSave=existing?.copy(startReading=s,remarks=r,status="PENDING")?:MeterReadingEntity(meterId=m,readingDate=d,startReading=s,endReading=0.0,consumption=0.0,remarks=r,status="PENDING")
       dao.insertMeterReading(toSave)
       SyncScheduler.runNow(context)
       "☀️ Morning reading recorded: $s kWh for $d. Day in progress."
      }.onSuccess{message=it}.onFailure{message=it.message}
     }
     dialog="";selectedMeterReading=null
    },
    onSaveEvening={existingId,d,m,s,e,r->
     scope.launch{
      runCatching{
       require(roleAllows(session,FactoryAction.MANAGE_ELECTRICITY)){"Your role cannot manage electricity readings"}
       val used=ElectricityRules.consumption(s,e)
       val existing=if(existingId!=null) dao.meterById(existingId) else dao.meterReadingByDate(m,d)
       val toSave=existing?.copy(startReading=s,endReading=e,consumption=used,remarks=r,status="COMPLETED")?:MeterReadingEntity(meterId=m,readingDate=d,startReading=s,endReading=e,consumption=used,remarks=r,status="COMPLETED")
       dao.insertMeterReading(toSave)
       SyncScheduler.runNow(context)
       "🌙 Day completed! Consumed: $used units ($s -> $e kWh)"
      }.onSuccess{message=it}.onFailure{message=it.message}
     }
     dialog="";selectedMeterReading=null
    },
    onSaveFull={d,m,s,e,r->
     scope.launch{
      runCatching{
       require(roleAllows(session,FactoryAction.MANAGE_ELECTRICITY)){"Your role cannot manage electricity readings"}
       val used=ElectricityRules.consumption(s,e)
       val existing=dao.meterReadingByDate(m,d)
       val toSave=existing?.copy(startReading=s,endReading=e,consumption=used,remarks=r,status="COMPLETED")?:MeterReadingEntity(meterId=m,readingDate=d,startReading=s,endReading=e,consumption=used,remarks=r,status="COMPLETED")
       dao.insertMeterReading(toSave)
       SyncScheduler.runNow(context)
       "⚡ Reading saved: $used units ($s -> $e kWh)"
      }.onSuccess{message=it}.onFailure{message=it.message}
     }
     dialog="";selectedMeterReading=null
    },
    onCatchUpAndStartToday={yesterdayReading,currentReading,todayDate->
     scope.launch{
      runCatching{
       require(roleAllows(session,FactoryAction.MANAGE_ELECTRICITY)){"Your role cannot manage electricity readings"}
       val used=ElectricityRules.consumption(yesterdayReading.startReading,currentReading)
       dao.insertMeterReading(yesterdayReading.copy(endReading=currentReading,consumption=used,status="COMPLETED",remarks=(yesterdayReading.remarks+" [Closed next morning at $currentReading kWh]").trim()))
       val todayExisting=dao.meterReadingByDate(yesterdayReading.meterId,todayDate)
       val todayEntry=todayExisting?.copy(startReading=currentReading,status="PENDING")?:MeterReadingEntity(meterId=yesterdayReading.meterId,readingDate=todayDate,startReading=currentReading,endReading=0.0,consumption=0.0,remarks="Carried over from yesterday close",status="PENDING")
       dao.insertMeterReading(todayEntry)
       SyncScheduler.runNow(context)
       "✅ Yesterday closed ($used units) & Today started at $currentReading kWh!"
      }.onSuccess{message=it}.onFailure{message=it.message}
     }
     dialog="";selectedMeterReading=null
    }
   )
  }
 if(dialog=="staff"){
  StaffForm(
   initial=editingStaff,
   close={dialog="";editingStaff=null},
   save={name,email,pass,role,modules->
    val existing=editingStaff
    scope.launch{
     runCatching{
      require(roleAllows(session,FactoryAction.MANAGE_STAFF)){"Only Administrators can manage staff accounts"}
      if(existing==null){
       FactoryAuthRepository(context).createStaffAccount(
        name=name,
        email=email,
        password=pass,
        role=role,
        allowedModules=modules.joinToString(","),
        creatorUid=session?.uid ?: error("Authenticated session missing"),
        session=session ?: error("Authenticated session missing")
       )
       "Staff account created for $email"
      }else{
       FactoryAuthRepository(context).updateStaffAccount(
        id=existing.id,
        uid=existing.uid,
        name=name,
        role=role,
        allowedModules=modules.joinToString(","),
        active=existing.active,
        session=session ?: error("Authenticated session missing")
       )
       "Staff member $name updated"
      }
     }.onSuccess{
      SyncScheduler.runNow(context)
      message=it
     }.onFailure{
      message=it.message?:"Failed to save staff member"
     }
    }
    dialog=""
    editingStaff=null
   }
  )
 }
 if(deletingProduct!=null){
  val p = deletingProduct!!
  AlertDialog(
   onDismissRequest={deletingProduct=null},
   title={Text("Archive Product",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Navy)},
   text={
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text("Archive this product from active operations? Production, packing, dispatch, stock movements and reports will retain its history.",fontSize=13.sp,color=Navy)
     Surface(color=Color(0xFFFFF0F0),shape=RoundedCornerShape(10.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFFFD1D1))){
      Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
       Text(p.name,fontWeight=FontWeight.Bold,fontSize=14.sp,color=Color(0xFFB42318))
       Text("${p.sizeLabel} · ${p.colour} · ${if(p.bottlesPerPack>0)"${p.bottlesPerPack}/pack" else "No pack size"}",fontSize=11.sp,color=Muted)
      }
     }
     Text("The product can be reactivated later. Existing records will not be deleted.",fontSize=11.sp,color=Muted)
    }
   },
   confirmButton={
    Button(
     onClick={
      scope.launch{
       runCatching{
        require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot archive products"}
        dao.removeProduct(p.id,session?.uid?:"local","Product deleted by administrator",session?.organisationId?:"local-org")
        SyncScheduler.runNow(context)
        "Product ${p.name} archived; history preserved"
       }.onSuccess{message=it}.onFailure{message=it.message}
      }
      deletingProduct=null
     },
     colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB42318)),
     shape=RoundedCornerShape(10.dp)
    ){
     Text("Archive Product",fontWeight=FontWeight.Bold)
    }
   },
   dismissButton={
    TextButton(onClick={deletingProduct=null}){Text("Cancel")}
   }
  )
 }
 if(deletingMachine!=null){
  val m = deletingMachine!!
  AlertDialog(
   onDismissRequest={deletingMachine=null},
   title={Text("Delete Machine",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Navy)},
   text={
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text("Are you sure you want to permanently remove this machine from the factory?",fontSize=13.sp,color=Navy)
     Surface(color=Color(0xFFFFF0F0),shape=RoundedCornerShape(10.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFFFD1D1))){
      Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
       Text(m.name,fontWeight=FontWeight.Bold,fontSize=14.sp,color=Color(0xFFB42318))
       Text("Code: ${m.machineCode} · Type: ${m.machineType} · ${m.location}",fontSize=11.sp,color=Muted)
      }
     }
     Text("This will remove the machine from schedules, lines and sync to the server.",fontSize=11.sp,color=Muted)
    }
   },
   confirmButton={
    Button(
     onClick={
      scope.launch{
       runCatching{
        require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot delete machines"}
        dao.removeMachine(m.id,session?.uid?:"local","Machine deleted by administrator",session?.organisationId?:"local-org")
        SyncScheduler.runNow(context)
        "Machine ${m.name} deleted"
       }.onSuccess{message=it}.onFailure{message=it.message}
      }
      deletingMachine=null
     },
     colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB42318)),
     shape=RoundedCornerShape(10.dp)
    ){
     Text("Delete Machine",fontWeight=FontWeight.Bold)
    }
   },
   dismissButton={
    TextButton(onClick={deletingMachine=null}){Text("Cancel")}
   }
  )
 }
 if(deletingLine!=null){
  val l = deletingLine!!
  AlertDialog(
   onDismissRequest={deletingLine=null},
   title={Text("Delete Production Line",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Navy)},
   text={
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text("Are you sure you want to permanently remove this production line?",fontSize=13.sp,color=Navy)
     Surface(color=Color(0xFFFFF0F0),shape=RoundedCornerShape(10.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFFFD1D1))){
      Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
       Text(l.name,fontWeight=FontWeight.Bold,fontSize=14.sp,color=Color(0xFFB42318))
       Text("Code: ${l.lineCode} · ${l.description}",fontSize=11.sp,color=Muted)
      }
     }
     Text("Any machines currently assigned to this line will become unassigned.",fontSize=11.sp,color=Muted)
    }
   },
   confirmButton={
    Button(
     onClick={
      scope.launch{
       runCatching{
        require(roleAllows(session,FactoryAction.MANAGE_MACHINES)){"Your role cannot delete production lines"}
        dao.removeProductionLine(l.id,session?.uid?:"local","Line deleted by administrator",session?.organisationId?:"local-org")
        SyncScheduler.runNow(context)
        "Production line ${l.name} deleted"
       }.onSuccess{message=it}.onFailure{message=it.message}
      }
      deletingLine=null
     },
     colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB42318)),
     shape=RoundedCornerShape(10.dp)
    ){
     Text("Delete Line",fontWeight=FontWeight.Bold)
    }
   },
   dismissButton={
    TextButton(onClick={deletingLine=null}){Text("Cancel")}
   }
  )
 }
 message?.let{
  Surface(
   shape = RoundedCornerShape(12.dp),
   color = Navy,
   shadowElevation = 6.dp,
   modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).padding(horizontal = 16.dp)
  ) {
   Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
  }
 }
}
}
}

@Composable private fun LineForm(initial:ProductionLineEntity?,close:()->Unit,save:(String,String,String,Int,String,String)->Unit){
 var code by remember(initial?.id){mutableStateOf(initial?.lineCode.orEmpty())}
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var desc by remember(initial?.id){mutableStateOf(initial?.description.orEmpty())}
 var capacity by remember(initial?.id){mutableStateOf(initial?.targetCapacity?.takeIf{it>0}?.toString().orEmpty())}
 var unit by remember(initial?.id){mutableStateOf(initial?.capacityUnit?:"BOTTLES_PER_HOUR")}
 var status by remember(initial?.id){mutableStateOf(initial?.status?:"ACTIVE")}
 val capInt=capacity.toIntOrNull()?:0
 val validCode=code.isNotBlank()&&code.trim().length<=24
 val validName=name.isNotBlank()&&name.trim().length<=80

 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text(if(initial==null)"Add production line" else "Edit production line",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
   Text(if(initial==null)"Create a factory line to group machines and measure flow." else "Update line details and operating capacity.",fontSize=12.sp,color=Muted)
  }},
  text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(14.dp)){
    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
     Icon(Icons.Default.PrecisionManufacturing,null,tint=Blue,modifier=Modifier.size(24.dp))
     Column{
      Text(name.trim().ifBlank{"New line preview"},fontWeight=FontWeight.Bold,color=Navy)
      Text(code.trim().ifBlank{"LINE-XX"}+(if(capInt>0)" · $capInt ${unit.lowercase().replace('_',' ')}" else ""),fontSize=11.sp,color=Muted)
     }
    }
   }
   OutlinedTextField(value=code,onValueChange={code=it.uppercase().filter{c->c.isLetterOrDigit()||c=='-'}.take(24)},label={Text("Line code *")},singleLine=true,isError=!validCode&&code.isNotBlank(),enabled=initial==null,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.QrCode,null)},placeholder={Text("e.g. LINE-03")},supportingText={if(initial!=null)Text("Line codes are unique and fixed.") else Text("Alphanumeric code, e.g. LINE-01")})
   OutlinedTextField(value=name,onValueChange={name=it},label={Text("Line name *")},singleLine=true,isError=!validName&&name.isNotBlank(),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Label,null)},placeholder={Text("e.g. 500ml Bottling Line 01")})
   OutlinedTextField(value=desc,onValueChange={desc=it},label={Text("Description / process type")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3,placeholder={Text("e.g. High-speed blowing, filling, capping and labelling process")})
   OutlinedTextField(value=capacity,onValueChange={capacity=it.filter(Char::isDigit).take(8)},label={Text("Target capacity (optional)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Speed,null)},placeholder={Text("e.g. 2400")},supportingText={Text("Hourly or daily design output rate.")})
   Text("Capacity unit",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    listOf("BOTTLES_PER_HOUR" to "Bottles/hr","BOTTLES_PER_SHIFT" to "Bottles/shift","PACKS_PER_HOUR" to "Packs/hr").forEach{(opt,lbl)->
     FilterChip(selected=unit==opt,onClick={unit=opt},label={Text(lbl,fontSize=10.sp)})
    }
   }
   Text("Line status",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    listOf("ACTIVE" to "Active","INACTIVE" to "Inactive").forEach{(opt,lbl)->
     FilterChip(selected=status==opt,onClick={status=opt},label={Text(lbl,fontSize=10.sp)})
    }
   }
  }},
  confirmButton={Button(enabled=validCode&&validName,onClick={save(code.trim(),name.trim(),desc.trim(),capInt,unit,status)},shape=RoundedCornerShape(12.dp)){Text(if(initial==null)"Create line" else "Save changes")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}

@Composable private fun AssignMachineLineForm(machine:MachineEntity,lines:List<ProductionLineEntity>,close:()->Unit,save:(Long?)->Unit){
 var selectedLineId by remember{mutableStateOf(machine.lineId)}
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text("Assign production line",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
   Text("Move equipment to an active production line or mark unassigned.",fontSize=12.sp,color=Muted)
  }},
  text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(14.dp)){
    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
     Icon(Icons.Default.Settings,null,tint=Blue,modifier=Modifier.size(24.dp))
     Column{
      Text(machine.name,fontWeight=FontWeight.Bold,color=Navy)
      Text(machine.machineCode+" · "+machine.machineType,fontSize=11.sp,color=Muted)
     }
    }
   }
   Text("SELECT PRODUCTION LINE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.2.sp)
   Surface(onClick={selectedLineId=null},color=if(selectedLineId==null)Color(0xFFEAF5FC) else Page,shape=RoundedCornerShape(12.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(selectedLineId==null)Blue else Border)){
    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
     RadioButton(selected=selectedLineId==null,onClick={selectedLineId=null})
     Column{
      Text("Unassigned (standby / shop floor asset)",fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp)
      Text("Asset is not tied to any production line",fontSize=10.sp,color=Muted)
     }
    }
   }
   lines.forEach{line->
    val isSelected=selectedLineId==line.id
    Surface(onClick={selectedLineId=line.id},color=if(isSelected)Color(0xFFEAF5FC) else Page,shape=RoundedCornerShape(12.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(isSelected)Blue else Border)){
     Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
      RadioButton(selected=isSelected,onClick={selectedLineId=line.id})
      Column{
       Text(line.name+" ("+line.lineCode+")",fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp)
       Text(if(line.status=="ACTIVE")"Active line · ${line.targetCapacity} ${line.capacityUnit.lowercase().replace('_',' ')}" else "Inactive line",fontSize=10.sp,color=if(line.status=="ACTIVE")Color(0xFF16804A) else Color(0xFFB45309))
      }
     }
    }
   }
  }},
  confirmButton={Button(onClick={save(selectedLineId)},shape=RoundedCornerShape(12.dp)){Text("Confirm assignment")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}

@Composable private fun MachineForm(initial:MachineEntity?,lines:List<ProductionLineEntity>,close:()->Unit,save:(String,String,String,String,String,Long?)->Unit){
 var code by remember(initial?.id){mutableStateOf(initial?.machineCode.orEmpty())}
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var type by remember(initial?.id){mutableStateOf(initial?.machineType?:"Filling")}
 var location by remember(initial?.id){mutableStateOf(initial?.location.orEmpty())}
 var notes by remember(initial?.id){mutableStateOf(initial?.notes.orEmpty())}
 var lineId by remember(initial?.id){mutableStateOf(initial?.lineId?:lines.firstOrNull()?.id)}
 val validCode=code.isNotBlank()&&code.trim().length<=24
 val validName=name.isNotBlank()&&name.trim().length<=80

 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text(if(initial==null)"Register machine" else "Edit machine details",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
   Text(if(initial==null)"Give every machine a unique asset code and assign its production line." else "Update asset attributes without altering service history.",fontSize=12.sp,color=Muted)
  }},
  text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
   OutlinedTextField(value=code,onValueChange={code=it.uppercase().filter{c->c.isLetterOrDigit()||c=='-'}.take(24)},label={Text("Asset code *")},singleLine=true,enabled=initial==null,isError=!validCode&&code.isNotBlank(),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.QrCode,null)},placeholder={Text("e.g. BLW-01")},supportingText={if(initial!=null)Text("Asset code is permanent.") else Text("Unique equipment ID")})
   OutlinedTextField(value=name,onValueChange={name=it},label={Text("Machine name *")},singleLine=true,isError=!validName&&name.isNotBlank(),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Settings,null)},placeholder={Text("e.g. Blowing Machine 01")})
   Text("Machine type",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   Column{
    listOf("Blowing","Filling","Capping","Labelling","Packing","Compressor","Other").chunked(3).forEach{row->
     Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
      row.forEach{t->FilterChip(selected=type==t,onClick={type=t},label={Text(t,fontSize=10.sp)})}
     }
    }
   }
   Text("Assigned production line",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
    FilterChip(selected=lineId==null,onClick={lineId=null},label={Text("Unassigned",fontSize=10.sp)})
    lines.filter{it.status=="ACTIVE"||it.id==initial?.lineId}.forEach{l->
     FilterChip(selected=lineId==l.id,onClick={lineId=l.id},label={Text(l.lineCode,fontSize=10.sp)})
    }
   }
   Field("Physical location / Bay",location){location=it}
   OutlinedTextField(notes,{notes=it},label={Text("Asset notes / specifications")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
  }},
  confirmButton={Button(enabled=validCode&&validName,onClick={save(code.trim(),name.trim(),type,location.trim(),notes.trim(),lineId)},shape=RoundedCornerShape(12.dp)){Text(if(initial==null)"Register machine" else "Save changes")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}

@Composable private fun ScheduleMaintenanceForm(machines:List<MachineEntity>,initialMachineId:Long?,close:()->Unit,save:(MachineEntity,String,String,String)->Unit){
 var selectedMachine by remember{mutableStateOf(machines.firstOrNull{it.id==initialMachineId}?:machines.firstOrNull())}
 var kind by remember{mutableStateOf("Preventive maintenance")}
 var reason by remember{mutableStateOf("")}
 var notes by remember{mutableStateOf("")}

 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text("Schedule maintenance",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
   Text("Open a preventive or corrective service work order for machinery.",fontSize=12.sp,color=Muted)
  }},
  text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("Equipment to service *",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   DropdownMachinePicker(machines,selectedMachine){selectedMachine=it}
   Text("Service category",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
    listOf("Preventive maintenance","Repair","Inspection","Lubrication").forEach{t->
     FilterChip(selected=kind==t,onClick={kind=t},label={Text(t,fontSize=10.sp)})
    }
   }
   Field("Work scope / reason *",reason){reason=it}
   OutlinedTextField(notes,{notes=it},label={Text("Required parts / instructions (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
  }},
  confirmButton={Button(enabled=selectedMachine!=null&&reason.isNotBlank(),onClick={selectedMachine?.let{save(it,kind,reason.trim(),notes.trim())}},shape=RoundedCornerShape(12.dp)){Text("Open service order")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}

@Composable private fun LogDowntimeForm(machines:List<MachineEntity>,initialMachineId:Long?,close:()->Unit,save:(MachineEntity,String,String)->Unit){
 var selectedMachine by remember{mutableStateOf(machines.firstOrNull{it.id==initialMachineId}?:machines.firstOrNull())}
 var reason by remember{mutableStateOf("")}
 var notes by remember{mutableStateOf("")}

 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text("Log machine stoppage",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
   Text("Record an unplanned breakdown or shop-floor stoppage.",fontSize=12.sp,color=Muted)
  }},
  text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("Affected equipment *",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
   DropdownMachinePicker(machines,selectedMachine){selectedMachine=it}
   Field("Stoppage / failure reason *",reason){reason=it}
   OutlinedTextField(notes,{notes=it},label={Text("Immediate action / observations (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
  }},
  confirmButton={Button(enabled=selectedMachine!=null&&reason.isNotBlank(),onClick={selectedMachine?.let{save(it,reason.trim(),notes.trim())}},shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB42318))){Text("Record stoppage")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}
@Composable private fun MachineEventForm(machine:MachineEntity,maintenance:Boolean,close:()->Unit,save:(String,String)->Unit){
 var reason by remember{mutableStateOf("")};var kind by remember{mutableStateOf(if(maintenance)"Preventive maintenance" else "Breakdown")};var notes by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text(if(maintenance)"Log maintenance" else "Log downtime",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){Text(machine.name+" · "+machine.machineCode,fontWeight=FontWeight.Bold,color=Blue);if(maintenance)Text("Maintenance type",fontSize=12.sp,color=Muted);if(maintenance)Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Preventive maintenance","Repair","Inspection","Lubrication").forEach{t->FilterChip(selected=kind==t,onClick={kind=t},label={Text(t,fontSize=10.sp)})}};Field(if(maintenance)"Work performed *" else "Downtime reason *",reason){reason=it};OutlinedTextField(notes,{notes=it},label={Text("Notes / parts used")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=reason.isNotBlank(),onClick={save(if(maintenance)kind else "Breakdown",reason.trim()+" "+notes.trim())}){Text("Save record")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun MachineEventCloseForm(event:MachineEventEntity,close:()->Unit,save:(String,String)->Unit){
 var completion by remember{mutableStateOf("")};var parts by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Complete maintenance / downtime",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){Text(event.machineNameSnapshot+" · "+event.eventType,fontWeight=FontWeight.Bold,color=Blue);val minutes=((System.currentTimeMillis()-event.startedAt).coerceAtLeast(0L)/60000L);Text("Open duration: "+(minutes/60)+"h "+(minutes%60)+"m",fontSize=12.sp,color=Muted);OutlinedTextField(completion,{completion=it},label={Text("Completion summary *")},modifier=Modifier.fillMaxWidth(),minLines=2);OutlinedTextField(parts,{parts=it},label={Text("Parts / materials used (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=completion.isNotBlank(),onClick={save(completion.trim(),parts.trim())}){Text("Save completion")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun QualityForm(products:List<ProductEntity>,production:List<ProductionEntryEntity>,close:()->Unit,save:(ProductEntity,ProductionEntryEntity?,String,Int,Int,Int,String,String,String)->Unit){
 var product by remember{mutableStateOf(products.firstOrNull())};var productionEntry by remember{mutableStateOf<ProductionEntryEntity?>(null)};var date by remember{mutableStateOf(today())};var inspected by remember{mutableStateOf("")};var passed by remember{mutableStateOf("")};var category by remember{mutableStateOf("Cap / seal defect")};var severity by remember{mutableStateOf("MINOR")};var notes by remember{mutableStateOf("")}
 val i=inspected.toIntOrNull()?:0;val p=passed.toIntOrNull()?:0;val failed=i-p
 AlertDialog(onDismissRequest=close,title={Text("Quality inspection",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Link this inspection to a production batch where possible. Failed quantity is calculated automatically.",fontSize=12.sp,color=Muted);Text("Production batch (optional)",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy);production.take(4).forEach{entry->FilterChip(selected=productionEntry?.id==entry.id,onClick={productionEntry=entry;product=products.firstOrNull{it.id==entry.productId}?:product},label={Text(entry.productionDate+" · "+entry.productNameSnapshot+" · good "+entry.goodBottles,fontSize=10.sp)})};if(productionEntry!=null)TextButton(onClick={productionEntry=null}){Text("Remove batch link")};ProductPicker(products,product){product=it;if(productionEntry?.productId!=it?.id)productionEntry=null};DateField(date){date=it};Field("Bottles inspected *",inspected){inspected=it.filter(Char::isDigit).take(9)};Field("Bottles passed *",passed){passed=it.filter(Char::isDigit).take(9)};Surface(color=if(failed>0)Color(0xFFFFF3DE) else Color(0xFFE8F7F0),shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("Failed bottles",Modifier.weight(1f),fontSize=12.sp,color=Navy);Text(failed.coerceAtLeast(0).toString(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=if(failed>0)Color(0xFFB45309) else Color(0xFF16804A))}};Text("Defect category",fontSize=12.sp,color=Muted);Column{listOf("Cap / seal defect","Leakage","Bottle deformation","Low fill","Contamination","Label / print defect","Colour / clarity","Other").chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){row.forEach{t->FilterChip(selected=category==t,onClick={category=t},label={Text(t,fontSize=10.sp)})}}}};Text("Severity",fontSize=12.sp,color=Muted);Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("MINOR","MAJOR","CRITICAL").forEach{t->FilterChip(selected=severity==t,onClick={severity=t},label={Text(t)})}};OutlinedTextField(notes,{notes=it},label={Text("Inspection notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=product!=null&&i>0&&p in 0..i&&date.length==10,onClick={save(product!!,productionEntry,date,i,p,failed,category,severity,notes.trim())}){Text("Save inspection")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun ProductionPlanForm(products:List<ProductEntity>,machines:List<MachineEntity>,close:()->Unit,save:(ProductEntity,String,String,Int,Long?,String)->Unit,initial:ProductionPlanEntity?=null){
 var product by remember{mutableStateOf(products.firstOrNull{it.id==initial?.productId}?:products.firstOrNull())};var date by remember{mutableStateOf(initial?.planDate?:today())};var shift by remember{mutableStateOf(initial?.shift?:"DAY")};var target by remember{mutableStateOf(initial?.targetBottles?.toString()?:"")};var machine by remember{mutableStateOf(machines.firstOrNull{it.id==initial?.assignedMachineId}?:machines.firstOrNull())};var notes by remember{mutableStateOf(initial?.notes?:"")}
 AlertDialog(onDismissRequest=close,title={Text(if(initial==null)"Create production plan" else "Edit production plan",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Set a target for a specific product, date and shift.",fontSize=12.sp,color=Muted);ProductPicker(products,product){product=it};DateField(date){date=it};Text("Shift",fontSize=12.sp,color=Muted);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("DAY","EVENING","NIGHT").forEach{t->FilterChip(selected=shift==t,onClick={shift=t},label={Text(t)})}};Field("Target bottles *",target){target=it.filter(Char::isDigit).take(9)};Text("Assigned machine (optional)",fontSize=12.sp,color=Muted);DropdownMachinePicker(machines,machine){machine=it};OutlinedTextField(notes,{notes=it},label={Text("Plan notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=product!=null&&(target.toIntOrNull()?:0)>0&&date.length==10,onClick={save(product!!,date,shift,target.toInt(),machine?.id,notes.trim())}){Text(if(initial==null)"Save plan" else "Save changes")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun DropdownMachinePicker(machines:List<MachineEntity>,selected:MachineEntity?,onSelect:(MachineEntity?)->Unit){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(selected?.name?:"No machine assigned")};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){DropdownMenuItem(text={Text("No machine assigned")},onClick={onSelect(null).let{expanded=false}});machines.forEach{m->DropdownMenuItem(text={Text(m.name+" · "+m.status)},onClick={onSelect(m).let{expanded=false}})}}}}
@Composable private fun SalesOrderForm(customers:List<CustomerEntity>,products:List<ProductEntity>,close:()->Unit,save:(CustomerEntity,String,String,String,List<Pair<ProductEntity,Int>>,String)->Unit,initial:SalesOrderEntity?=null,initialLines:List<SalesOrderLineEntity> = emptyList()){
 var customer by remember{mutableStateOf(customers.firstOrNull{it.id==initial?.customerId}?:customers.firstOrNull())};var number by remember{mutableStateOf(initial?.orderNumber?:"SO-"+System.currentTimeMillis().toString().takeLast(7))};var date by remember{mutableStateOf(initial?.orderDate?:today())};var due by remember{mutableStateOf(initial?.dueDate?:today())};var notes by remember{mutableStateOf(initial?.notes?:"")}
 var selectedProduct by remember{mutableStateOf(products.firstOrNull{it.active&&it.bottlesPerPack>0})};var packs by remember{mutableStateOf("")};var lines by remember{mutableStateOf(initialLines.mapNotNull{line->products.firstOrNull{it.id==line.productId}?.let{it to line.packsOrdered}})}
 AlertDialog(onDismissRequest=close,title={Text(if(initial==null)"New sales order" else "Edit sales order",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Add multiple products to one customer order.",fontSize=12.sp,color=Muted);CustomerPicker(customers,customer){customer=it};Field("Order number *",number){number=it.trim().take(40)};DateField(date){date=it};DateField(due){due=it};ProductPicker(products.filter{it.active&&it.bottlesPerPack>0},selectedProduct){selectedProduct=it};Field("Packs for selected product *",packs){packs=it.filter(Char::isDigit).take(9)};Button(enabled=selectedProduct!=null&&(packs.toIntOrNull()?:0)>0,onClick={val p=selectedProduct!!;val q=packs.toInt();val index=lines.indexOfFirst{it.first.id==p.id};lines=if(index>=0)lines.mapIndexed{ i,item->if(i==index)item.first to (item.second+q) else item}else lines+(p to q);packs="";selectedProduct=products.firstOrNull{it.active&&it.bottlesPerPack>0&&lines.none{line->line.first.id==it.id}}?:products.firstOrNull{it.active&&it.bottlesPerPack>0}}){Text("Add product to order")};if(lines.isNotEmpty()){Text("Order lines (${lines.size})",fontWeight=FontWeight.Bold,color=Navy);lines.forEach{(p,q)->Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.SemiBold,color=Navy);Text("$q packs · ${q*p.bottlesPerPack} bottles",fontSize=11.sp,color=Muted)};TextButton(onClick={lines=lines.filterNot{it.first.id==p.id}}){Text("Remove")}}}};OutlinedTextField(notes,{notes=it},label={Text("Order notes")},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(enabled=customer!=null&&number.isNotBlank()&&lines.isNotEmpty()&&date.length==10&&due.length==10,onClick={save(customer!!,number,date,due,lines,notes.trim())}){Text(if(initial==null)"Create order" else "Save changes")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun StockMiniMetric(title:String,value:Int,modifier:Modifier=Modifier){
 Surface(modifier,color=Page,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(10.dp)){Text(title,fontSize=10.sp,color=Muted);Text(value.toString(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)}}
}
@Composable private fun StockMiniMetric(title:String,valueText:String,modifier:Modifier=Modifier){
 Surface(modifier,color=Page,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(10.dp)){Text(title,fontSize=10.sp,color=Muted);Text(valueText,fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)}}
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
@Composable private fun ProductPicker(products:List<ProductEntity>,selected:ProductEntity?,onSelect:(ProductEntity?)->Unit){
 var expanded by remember{mutableStateOf(false)}
 Box{
  OutlinedButton(
   onClick={expanded=true},
   modifier=Modifier.fillMaxWidth(),
   shape=RoundedCornerShape(12.dp),
   contentPadding=PaddingValues(horizontal=12.dp,vertical=9.dp)
  ){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()){
    if(selected!=null){
     ProductThumbnail(imageUri=selected.imageUri,iconName=selected.iconName,colour=selected.colour,bottleType="${selected.sizeLabel} ${selected.colour} ${selected.name}",size=30.dp)
     Column(Modifier.weight(1f)){
      Text(selected.name,fontWeight=FontWeight.SemiBold,color=Navy)
      Text(selected.sizeLabel+" · "+selected.colour+(if(selected.bottlesPerPack>0)" · ${selected.bottlesPerPack}/pack" else ""),fontSize=10.sp,color=Muted)
     }
    }else{
     Icon(Icons.Default.WaterDrop,null,tint=Blue,modifier=Modifier.size(20.dp))
     Text("Select bottle product",Modifier.weight(1f),color=Muted)
    }
    Icon(Icons.Default.ArrowDropDown,null,tint=Navy)
   }
  }
  DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.fillMaxWidth(0.9f)){
   if(products.isEmpty()){
    DropdownMenuItem(text={Text("No products configured")},onClick={expanded=false})
   }
   products.forEach{p->
    DropdownMenuItem(
     leadingIcon={ProductThumbnail(imageUri=p.imageUri,iconName=p.iconName,colour=p.colour,bottleType="${p.sizeLabel} ${p.colour} ${p.name}",size=32.dp)},
     text={
      Column{
       Text(p.name,fontWeight=FontWeight.SemiBold,color=Navy)
       Text(p.sizeLabel+" · "+p.colour+(if(p.bottlesPerPack>0)" · ${p.bottlesPerPack} bottles/pack" else ""),fontSize=10.sp,color=Muted)
      }
     },
     onClick={onSelect(p);expanded=false}
    )
   }
  }
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProductForm(initial:ProductEntity?,close:()->Unit,save:(String,String,String,Int,String,String)->Unit){
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var size by remember(initial?.id){mutableStateOf(initial?.sizeLabel.orEmpty())}
 var colour by remember(initial?.id){mutableStateOf(initial?.colour?:"Clear")}
 var pack by remember(initial?.id){mutableStateOf((initial?.bottlesPerPack?:0).let{if(it>0)it.toString() else ""})}
 var iconName by remember(initial?.id){mutableStateOf(initial?.iconName?:"bottle_water")}
 var imageUri by remember(initial?.id){mutableStateOf(initial?.imageUri.orEmpty())}
 var colourExpanded by remember{mutableStateOf(false)}
 val packCount=pack.toIntOrNull()
 val validName=name.isNotBlank()&&name.trim().length<=80
 val validSize=size.isNotBlank()&&size.trim().length<=40
 val validPack=pack.isBlank()||(packCount!=null&&packCount in 1..100000)
 val context=LocalContext.current
 val photoLauncher=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
  if(uri!=null){
   runCatching{
    val dir=File(context.filesDir,"product_images")
    if(!dir.exists())dir.mkdirs()
    val dest=File(dir,"bottle_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use{input->dest.outputStream().use{output->input.copyTo(output)}}
    imageUri=dest.absolutePath
   }
  }
 }
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text(if(initial==null)"Add bottle product" else "Edit bottle product",fontWeight=FontWeight.ExtraBold,color=Navy,fontSize=22.sp)
   Text(if(initial==null)"Configure bottle shape, size, photo, and pack size." else "Update configuration without modifying existing transactions.",fontSize=12.sp,color=Muted)
  }},
  text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(11.dp)){
   Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(16.dp)){
    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
     ProductThumbnail(imageUri=imageUri,iconName=iconName,colour=colour,bottleType=size,size=54.dp)
     Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
      Text(name.trim().ifBlank{"New bottle product"},fontWeight=FontWeight.Bold,color=Navy)
      Text(size.trim().ifBlank{"Bottle size not set"}+" · "+colour, fontSize=11.sp,color=Muted)
      Text(if(packCount!=null&&packCount>0)"$packCount bottles per pack" else "Pack configuration required for packing",fontSize=11.sp,color=if(packCount!=null&&packCount>0)Color(0xFF16804A) else Color(0xFF9B5B00))
     }
    }
   }
   Surface(color=Color.White,shape=RoundedCornerShape(14.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
     Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
      Icon(Icons.Default.PhotoCamera,null,tint=Blue,modifier=Modifier.size(20.dp))
      Text("Custom bottle photo",fontWeight=FontWeight.Bold,color=Navy,fontSize=12.sp,modifier=Modifier.weight(1f))
      if(imageUri.isNotBlank()){
       TextButton(onClick={imageUri=""}){Text("Remove",color=Color(0xFFB42318),fontSize=11.sp)}
      }
     }
     if(imageUri.isNotBlank()){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
       ProductThumbnail(imageUri=imageUri,size=46.dp)
       Column(Modifier.weight(1f)){
        Text("Custom photo attached",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Navy)
        Text("Saved in app internal storage",fontSize=10.sp,color=Muted)
       }
       OutlinedButton(onClick={photoLauncher.launch("image/*")},shape=RoundedCornerShape(10.dp)){Text("Change",fontSize=11.sp)}
      }
     }else{
      Button(onClick={photoLauncher.launch("image/*")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF0F3D6F))){
       Icon(Icons.Default.AddPhotoAlternate,null,modifier=Modifier.size(18.dp))
       Spacer(Modifier.width(6.dp))
       Text("Upload bottle photo from device",fontSize=12.sp)
      }
     }
    }
   }
   BottleIconPicker(selectedIcon=iconName,onSelect={iconName=it})
   OutlinedTextField(value=name,onValueChange={name=it},label={Text("Product name *")},singleLine=true,isError=!validName,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Label,null)},supportingText={if(!validName)Text("Enter a name up to 80 characters.")})
   OutlinedTextField(value=size,onValueChange={size=it},label={Text("Bottle size / type *")},singleLine=true,isError=!validSize,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Straighten,null)},placeholder={Text("e.g. 500 ml")},supportingText={if(!validSize)Text("Enter the bottle size or type.")})
   ExposedDropdownMenuBox(expanded=colourExpanded,onExpandedChange={colourExpanded=it}){
    OutlinedTextField(value=colour,onValueChange={colour=it},readOnly=true,label={Text("Bottle colour")},singleLine=true,modifier=Modifier.menuAnchor().fillMaxWidth(),leadingIcon={Icon(Icons.Default.Palette,null)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=colourExpanded)})
    ExposedDropdownMenu(expanded=colourExpanded,onDismissRequest={colourExpanded=false}){listOf("Clear","Green","Milk White","Blue","Amber","Other").forEach{option->DropdownMenuItem(text={Text(option)},onClick={colour=option;colourExpanded=false})}}
   }
   OutlinedTextField(value=pack,onValueChange={pack=it.filter(Char::isDigit).take(6)},label={Text("Bottles per pack")},singleLine=true,isError=!validPack,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Inventory2,null)},placeholder={Text("e.g. 24")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),supportingText={Text(if(pack.isBlank())"Optional during setup; packing stays blocked until configured." else if(!validPack)"Enter a quantity from 1 to 100000." else "Used to calculate complete packs and loose bottles.")})
   if(initial!=null)Text("Product ID: "+initial.id+" · Existing history is retained.",fontSize=10.sp,color=Muted)
  }},
  confirmButton={Button(enabled=validName&&validSize&&validPack,onClick={save(name.trim(),size.trim(),colour.trim(),packCount?:0,iconName,imageUri)},shape=RoundedCornerShape(12.dp)){Text(if(initial==null)"Save product" else "Save changes")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProductionForm(
 products: List<ProductEntity>,
 lines: List<ProductionLineEntity>,
 machines: List<MachineEntity>,
 close: () -> Unit,
 save: (ProductEntity?, String, Int, Int, Int, Long?, String, Long?, String, String) -> Unit
){
 var p by remember{mutableStateOf(products.firstOrNull())}
 var d by remember{mutableStateOf(today())}
 var shift by remember{mutableStateOf("Day")}
 val activeLines = remember(lines){lines.filter{it.status.uppercase()=="ACTIVE"}.ifEmpty{lines}}
 var selectedLine by remember(lines){mutableStateOf(activeLines.firstOrNull())}
 var lineMenuExpanded by remember{mutableStateOf(false)}
 val eligibleMachines = remember(selectedLine?.id,machines){machines.filter{selectedLine==null||it.lineId==selectedLine?.id}}
 var selectedMachine by remember(selectedLine?.id,machines){mutableStateOf(eligibleMachines.firstOrNull{it.status.uppercase()=="RUNNING"}?:eligibleMachines.firstOrNull())}
 var machineMenuExpanded by remember{mutableStateOf(false)}
 var operatorName by remember{mutableStateOf("")}
 var total by remember{mutableStateOf("")}
 var good by remember{mutableStateOf("")}
 var rejected by remember{mutableStateOf("0")}
 var rejectReason by remember{mutableStateOf("")}
 var remarks by remember{mutableStateOf("")}
 val t=total.toIntOrNull();val g=good.toIntOrNull();val r=rejected.toIntOrNull()
 val quantitiesValid=t!=null&&g!=null&&r!=null&&t>0&&g>=0&&r>=0&&g+r==t
 val dateValid=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(d)!=null}.getOrDefault(false)&&d.length==10
 val machineIsRunning = selectedMachine==null || selectedMachine?.status?.uppercase()=="RUNNING"
 val canSave = p!=null && dateValid && quantitiesValid && machineIsRunning
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text("Add production run",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Record output for line, machine, shift and product.",fontSize=12.sp,color=Muted)}},
  text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Build,null,tint=Blue,modifier=Modifier.padding(10.dp))}
    Column{Text("Step 1 · Line & machine assignment",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp);Text("Connect physical line and active asset",fontSize=11.sp,color=Muted)}
   }
   ExposedDropdownMenuBox(expanded=lineMenuExpanded,onExpandedChange={lineMenuExpanded=it}){
    OutlinedTextField(
     value=selectedLine?.let{"${it.lineCode} · ${it.name}"}?:"No line configured",
     onValueChange={},
     readOnly=true,
     label={Text("Production line *")},
     singleLine=true,
     modifier=Modifier.menuAnchor().fillMaxWidth(),
     leadingIcon={Icon(Icons.Default.PrecisionManufacturing,null,tint=Blue)},
     trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=lineMenuExpanded)}
    )
    ExposedDropdownMenu(expanded=lineMenuExpanded,onDismissRequest={lineMenuExpanded=false}){
     activeLines.forEach{line->
      DropdownMenuItem(
       text={Column{Text(line.name,fontWeight=FontWeight.SemiBold);Text("${line.lineCode} · Capacity: ${line.targetCapacity} ${line.capacityUnit}",fontSize=11.sp,color=Muted)}},
       onClick={
        selectedLine=line
        val newEligible=machines.filter{it.lineId==line.id}
        selectedMachine=newEligible.firstOrNull{it.status.uppercase()=="RUNNING"}?:newEligible.firstOrNull()
        lineMenuExpanded=false
       }
      )
     }
    }
   }
   ExposedDropdownMenuBox(expanded=machineMenuExpanded,onExpandedChange={machineMenuExpanded=it}){
    OutlinedTextField(
     value=selectedMachine?.let{"${it.name} (${it.status})"}?:"General / Unassigned",
     onValueChange={},
     readOnly=true,
     label={Text("Assigned machine")},
     singleLine=true,
     modifier=Modifier.menuAnchor().fillMaxWidth(),
     leadingIcon={Icon(Icons.Default.Settings,null,tint=if(machineIsRunning) Blue else Color(0xFFB42318))},
     trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=machineMenuExpanded)}
    )
    ExposedDropdownMenu(expanded=machineMenuExpanded,onDismissRequest={machineMenuExpanded=false}){
     DropdownMenuItem(text={Text("General / Line-wide (no specific machine)")},onClick={selectedMachine=null;machineMenuExpanded=false})
     eligibleMachines.forEach{m->
      DropdownMenuItem(
       text={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text(m.name,fontWeight=FontWeight.SemiBold);Text(m.machineType,fontSize=11.sp,color=Muted)};Surface(color=when(m.status.uppercase()){"RUNNING"->Color(0xFFE8F7F0);"MAINTENANCE"->Color(0xFFFEF4E6);else->Color(0xFFFFF0F0)},shape=RoundedCornerShape(6.dp)){Text(m.status,fontSize=10.sp,fontWeight=FontWeight.Bold,color=when(m.status.uppercase()){"RUNNING"->Color(0xFF16804A);"MAINTENANCE"->Color(0xFF9B5B00);else->Color(0xFFB42318)},modifier=Modifier.padding(horizontal=6.dp,vertical=2.dp))}}},
       onClick={selectedMachine=m;machineMenuExpanded=false}
      )
     }
    }
   }
   if(!machineIsRunning&&selectedMachine!=null){
    Surface(color=Color(0xFFFFF0F0),shape=RoundedCornerShape(10.dp)){
     Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
      Icon(Icons.Default.WarningAmber,null,tint=Color(0xFFB42318),modifier=Modifier.size(20.dp))
      Text("Machine '${selectedMachine?.name}' is ${selectedMachine?.status}. Only RUNNING equipment can record production. Select an operational machine or service this asset.",fontSize=11.sp,color=Color(0xFFB42318))
     }
    }
   }
   DateField(d){d=it}
   if(!dateValid)Text("Use a valid date in YYYY-MM-DD format.",fontSize=11.sp,color=Color(0xFFB42318))
   Text("Shift",fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    listOf("Day","Evening","Night").forEach{value->FilterChip(selected=shift==value,onClick={shift=value},label={Text(value)},leadingIcon=if(shift==value)({Icon(Icons.Default.Check,null,modifier=Modifier.size(16.dp))}) else null)}
   }
   OutlinedTextField(operatorName,{operatorName=it},label={Text("Operator / Shift lead (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Person,null)})
   HorizontalDivider(color=Border)
   Text("Step 2 · Product selection",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
   ProductPicker(products,p){p=it}
   p?.let{
    Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){
     Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
      Icon(Icons.Default.WaterDrop,null,tint=Blue)
      Column(Modifier.weight(1f)){Text(it.name,fontWeight=FontWeight.Bold,color=Navy);Text("${it.sizeLabel} · ${it.colour} · Pack size: ${if(it.bottlesPerPack>0) "${it.bottlesPerPack} bottles" else "Unconfigured"}",fontSize=11.sp,color=Muted)}
     }
    }
   }
   HorizontalDivider(color=Border)
   Text("Step 3 · Production reconciliation",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
   OutlinedTextField(total,{total=it.filter(Char::isDigit).take(9)},label={Text("Total produced bottles *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Build,null)})
   OutlinedTextField(good,{good=it.filter(Char::isDigit).take(9)},label={Text("Good bottles *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.CheckCircle,null)})
   OutlinedTextField(rejected,{rejected=it.filter(Char::isDigit).take(9)},label={Text("Rejected bottles *")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.WarningAmber,null)})
   if(t!=null&&g!=null&&r!=null){
    val valid=t>0&&g>=0&&r>=0&&g+r==t
    Surface(color=if(valid)Color(0xFFE8F7F0) else Color(0xFFFFF0F0),shape=RoundedCornerShape(12.dp)){
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
       Icon(if(valid)Icons.Default.CheckCircle else Icons.Default.Error,null,tint=if(valid)Color(0xFF16804A) else Color(0xFFB42318))
       Text(if(valid)"Reconciliation balanced" else "Reconciliation mismatch",fontWeight=FontWeight.Bold,color=if(valid)Color(0xFF16804A) else Color(0xFFB42318),fontSize=12.sp)
      }
      Text("Produced $t = Good $g + Rejected $r ${if(!valid)"(Discrepancy: ${t - (g + r)})" else ""}",fontSize=11.sp,color=Navy)
     }
    }
   }
   OutlinedTextField(rejectReason,{rejectReason=it},label={Text("Reject / Stoppage reason (if any)")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.WarningAmber,null)})
   OutlinedTextField(remarks,{remarks=it},label={Text("Production notes (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
   if(canSave){
    Surface(color=Color(0xFFF1F7FC),shape=RoundedCornerShape(10.dp)){
     Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
      Text("Run summary:",fontWeight=FontWeight.SemiBold,fontSize=11.sp,color=Navy)
      Text("${selectedLine?.name?:"General line"} · ${selectedMachine?.name?:"Unassigned machine"} · Shift $shift",fontSize=10.sp,color=Muted)
      Text("Product: ${p?.name} · $t total ($g good, $r rejected)",fontSize=10.sp,color=Navy,fontWeight=FontWeight.Medium)
     }
    }
   }
  }},
  confirmButton={Button(
   enabled=canSave,
   onClick={
    val combinedRemarks=listOfNotNull(
     if(shift.isNotBlank())"Shift: $shift" else null,
     if(operatorName.isNotBlank())"Operator: ${operatorName.trim()}" else null,
     if(rejectReason.isNotBlank())"Reject reason: ${rejectReason.trim()}" else null,
     remarks.trim().ifBlank{null}
    ).joinToString(" · ")
    save(
     p,
     d,
     t!!,
     g!!,
     r!!,
     selectedLine?.id,
     selectedLine?.name?:"",
     selectedMachine?.id,
     selectedMachine?.name?:(selectedLine?.name?:"Machine 1"),
     combinedRemarks
    )
   },
   shape=RoundedCornerShape(12.dp)
  ){Text("Review & save run")}},
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}
@Composable private fun PackingForm(products:List<ProductEntity>,dao:FactoryDao,close:()->Unit,cloudAvailableBottles:(ProductEntity?)->Int?={null},save:(ProductEntity?,String,Int)->Unit){
 var p by remember{mutableStateOf(products.firstOrNull())}
 var d by remember{mutableStateOf(today())}
 var bottles by remember{mutableStateOf("")}
 var available by remember{mutableIntStateOf(0)}
 val n=bottles.toIntOrNull()?:0
 val serverAvailable=cloudAvailableBottles(p)
 val calc=runCatching{PackCalculation.calculate(n,p?.bottlesPerPack?:0)}.getOrNull()
 val dateValid=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(d)!=null}.getOrDefault(false)&&d.length==10
 LaunchedEffect(p?.id,serverAvailable){available=serverAvailable?:p?.let{(dao.totalGoodBottles(it.id)-dao.totalPackedBottles(it.id)).coerceAtLeast(0)}?:0}
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text("Add packing batch",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text("Convert available good bottles into finished packs.",fontSize=12.sp,color=Muted)}},
  text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(color=Color(0xFFF0EBFF),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Inventory2,null,tint=Color(0xFF7656D9),modifier=Modifier.padding(10.dp))}
    Column{Text("Packing details",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp);Text("Product · Date · Quantity",fontSize=11.sp,color=Muted)}
   }
   ProductPicker(products,p){p=it;bottles=""}
   p?.let{Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.WaterDrop,null,tint=Blue,modifier=Modifier.size(24.dp));Column(Modifier.weight(1f)){Text(it.name,fontWeight=FontWeight.Bold,color=Navy);Text(if(it.bottlesPerPack>0)"Pack size: "+it.bottlesPerPack+" bottles" else "Pack size not configured",fontSize=11.sp,color=Muted)};Column(horizontalAlignment=Alignment.End){Text(available.toString(),fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(if(serverAvailable!=null)"server-available bottles" else "local available bottles",fontSize=9.sp,color=Muted)}}}}
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
@Composable
private fun MeterForm(
 initialMode:String,
 initialReading:MeterReadingEntity?,
 meters:List<MeterReadingEntity>,
 close:()->Unit,
 onSaveMorning:(String,String,Double,String)->Unit,
 onSaveEvening:(Long?,String,String,Double,Double,String)->Unit,
 onSaveFull:(String,String,Double,Double,String)->Unit,
 onCatchUpAndStartToday:(MeterReadingEntity,Double,String)->Unit
){
 var mode by remember{mutableStateOf(initialMode)}
 val latestCompleted=remember(meters){meters.firstOrNull{it.status=="COMPLETED"}}
 val pendingReading=remember(meters,initialReading){initialReading?:meters.firstOrNull{it.status=="PENDING"}}

 var d by remember{mutableStateOf(initialReading?.readingDate?:today())}
 var meter by remember{mutableStateOf(initialReading?.meterId?:"MAIN")}
 var start by remember{
  mutableStateOf(
   initialReading?.startReading?.let{String.format(Locale.US,"%.1f",it)}
    ?: latestCompleted?.endReading?.let{String.format(Locale.US,"%.1f",it)}
    ?: ""
  )
 }
 var end by remember{mutableStateOf(if(initialReading?.endReading!=null&&initialReading.endReading>0.0) String.format(Locale.US,"%.1f",initialReading.endReading) else "")}
 var currentMeterInput by remember{mutableStateOf("")}
 var remarks by remember{mutableStateOf(initialReading?.remarks.orEmpty())}

 val startVal=start.toDoubleOrNull()
 val endVal=end.toDoubleOrNull()
 val currentVal=currentMeterInput.toDoubleOrNull()
 val consumption=if(startVal!=null&&endVal!=null&&endVal>=startVal){
  runCatching{ElectricityRules.consumption(startVal,endVal)}.getOrNull()
 }else null

 val catchUpConsumption=if(pendingReading!=null&&currentVal!=null&&currentVal>=pendingReading.startReading){
  runCatching{ElectricityRules.consumption(pendingReading.startReading,currentVal)}.getOrNull()
 }else null

 AlertDialog(
  onDismissRequest=close,
  title={
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(
     when(mode){
      "MORNING"->"☀️ Morning Reading (Day Start)"
      "EVENING"->"🌙 Evening Reading (Day End)"
      "CATCH_UP"->"⚡ Catch-Up & Start Today"
      else->"⚡ Full Day Reading"
     },
     fontSize=20.sp,
     fontWeight=FontWeight.ExtraBold,
     color=Navy
    )
    Text(
     when(mode){
      "MORNING"->"Record initial meter reading when operations begin."
      "EVENING"->"Record day-end meter reading to calculate daily power usage."
      "CATCH_UP"->"Close missed previous reading and start today automatically."
      else->"Record both start and end readings together."
     },
     fontSize=12.sp,
     color=Muted
    )
   }
  },
  text={
   Column(
    modifier=Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement=Arrangement.spacedBy(10.dp)
   ){
    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
     FilterChip(
      selected=mode=="MORNING",
      onClick={mode="MORNING"},
      label={Text("☀️ Morning",fontSize=11.sp)},
      modifier=Modifier.weight(1f)
     )
     FilterChip(
      selected=mode=="EVENING",
      onClick={mode="EVENING"},
      label={Text("🌙 Evening",fontSize=11.sp)},
      modifier=Modifier.weight(1f)
     )
     FilterChip(
      selected=mode=="FULL",
      onClick={mode="FULL"},
      label={Text("⚡ Full",fontSize=11.sp)},
      modifier=Modifier.weight(1f)
     )
    }

    when(mode){
     "MORNING"->{
      DateField(d){d=it}
      OutlinedTextField(meter,{meter=it},label={Text("Meter ID")},modifier=Modifier.fillMaxWidth(),singleLine=true)
      Field("Morning Start Reading (kWh)",start,{start=it},true)
      if(latestCompleted!=null&&startVal==latestCompleted.endReading){
       Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(8.dp)){
        Text("✨ Pre-filled from previous day end (${latestCompleted.endReading} kWh on ${latestCompleted.readingDate})",fontSize=11.sp,color=Blue,modifier=Modifier.padding(8.dp))
       }
      }
      OutlinedTextField(remarks,{remarks=it},label={Text("Remarks (optional)")},modifier=Modifier.fillMaxWidth())
     }
     "EVENING"->{
      if(pendingReading!=null){
       Surface(
        color=Color(0xFFF0FDF4),
        shape=RoundedCornerShape(12.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFBBF7D0))
       ){
        Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
         Text("Day Start Reading (Recorded)",fontWeight=FontWeight.Bold,color=Color(0xFF166534),fontSize=11.sp)
         Text("Date: ${pendingReading.readingDate} · Meter: ${pendingReading.meterId}",fontSize=12.sp,color=Navy)
         Text("Start: ${pendingReading.startReading} kWh",fontSize=14.sp,fontWeight=FontWeight.ExtraBold,color=Blue)
        }
       }
       Field("Evening End Reading (kWh)",end,{end=it},true)
      }else{
       DateField(d){d=it}
       Field("Start Reading (kWh)",start,{start=it},true)
       Field("Evening End Reading (kWh)",end,{end=it},true)
      }

      if(endVal!=null&&startVal!=null){
       if(endVal>=startVal){
        Surface(color=Color(0xFFE0F2FE),shape=RoundedCornerShape(10.dp)){
         Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
          Text("Power Consumed Today:",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
          Text("${consumption?:0.0} units",fontWeight=FontWeight.ExtraBold,color=Blue,fontSize=16.sp)
         }
        }
       }else{
        Text("⚠️ Evening reading cannot be below start reading ($startVal kWh)",color=Color(0xFFDC2626),fontSize=11.sp,fontWeight=FontWeight.Bold)
       }
      }
      OutlinedTextField(remarks,{remarks=it},label={Text("Remarks (optional)")},modifier=Modifier.fillMaxWidth())
     }
     "CATCH_UP"->{
      if(pendingReading!=null){
       Surface(
        color=Color(0xFFFEF3C7),
        shape=RoundedCornerShape(12.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFFDE68A))
       ){
        Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
         Text("Missed Evening Reading",fontWeight=FontWeight.Bold,color=Color(0xFF92400E),fontSize=11.sp)
         Text("Date: ${pendingReading.readingDate} · Start was: ${pendingReading.startReading} kWh",fontSize=12.sp,color=Navy)
        }
       }
       Text("Enter current meter reading right now. It will close ${pendingReading.readingDate}'s usage and start today's (${today()}) reading at this value!",fontSize=12.sp,color=Muted)
       Field("Current Meter Reading (kWh)",currentMeterInput,{currentMeterInput=it},true)
       if(catchUpConsumption!=null){
        Surface(color=Color(0xFFDCFCE7),shape=RoundedCornerShape(10.dp)){
         Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
          Text("Yesterday usage: $catchUpConsumption units",fontWeight=FontWeight.Bold,color=Color(0xFF166534),fontSize=12.sp)
          Text("Today will start at: $currentVal kWh",fontSize=11.sp,color=Color(0xFF15803D))
         }
        }
       }
      }
     }
     "FULL"->{
      DateField(d){d=it}
      OutlinedTextField(meter,{meter=it},label={Text("Meter ID")},modifier=Modifier.fillMaxWidth(),singleLine=true)
      Field("Start Reading (kWh)",start,{start=it},true)
      Field("End Reading (kWh)",end,{end=it},true)
      if(consumption!=null){
       Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(8.dp)){
        Text("Consumption: $consumption units",color=Blue,fontWeight=FontWeight.Bold,modifier=Modifier.padding(8.dp))
       }
      }
      OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")},modifier=Modifier.fillMaxWidth())
     }
    }
   }
  },
  confirmButton={
   when(mode){
    "MORNING"->{
     Button(
      enabled=startVal!=null&&startVal>=0.0,
      onClick={onSaveMorning(d,meter,startVal!!,remarks)},
      shape=RoundedCornerShape(10.dp)
     ){
      Text("Save Morning Reading")
     }
    }
    "EVENING"->{
     Button(
      enabled=consumption!=null,
      onClick={
       val targetId=pendingReading?.id
       val targetDate=pendingReading?.readingDate?:d
       val targetMeter=pendingReading?.meterId?:meter
       val targetStart=pendingReading?.startReading?:startVal!!
       onSaveEvening(targetId,targetDate,targetMeter,targetStart,endVal!!,remarks)
      },
      shape=RoundedCornerShape(10.dp)
     ){
      Text("Save & Complete Day")
     }
    }
    "CATCH_UP"->{
     Button(
      enabled=catchUpConsumption!=null&&pendingReading!=null,
      onClick={
       if(pendingReading!=null&&currentVal!=null){
        onCatchUpAndStartToday(pendingReading,currentVal,today())
       }
      },
      shape=RoundedCornerShape(10.dp),
      colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFD97706))
     ){
      Text("Close & Start Today")
     }
    }
    else->{
     Button(
      enabled=consumption!=null,
      onClick={onSaveFull(d,meter,startVal!!,endVal!!,remarks)},
      shape=RoundedCornerShape(10.dp)
     ){
      Text("Save Reading")
     }
    }
   }
  },
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}
@Composable private fun CustomerForm(initial:CustomerEntity?,close:()->Unit,save:(String,String,String,String)->Unit){
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var phone by remember(initial?.id){mutableStateOf(initial?.phone.orEmpty())}
 var address by remember(initial?.id){mutableStateOf(initial?.address.orEmpty())}
 var gst by remember(initial?.id){mutableStateOf(initial?.gstNumber.orEmpty())}
 AlertDialog(onDismissRequest=close,title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(initial==null)"Add customer" else "Edit customer",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(if(initial==null)"Save customer contact details for dispatch." else "Update contact details. Existing dispatch snapshots remain unchanged.",fontSize=12.sp,color=Muted)}},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(9.dp)){
  Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(14.dp)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(color=Color.White,shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.People,null,tint=Blue,modifier=Modifier.padding(10.dp))};Column{Text(name.trim().ifBlank{"Customer preview"},fontWeight=FontWeight.Bold,color=Navy);Text(phone.ifBlank{"Phone not provided"},fontSize=11.sp,color=Muted)}}}
  OutlinedTextField(name,{name=it},label={Text("Customer name *")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Person,null)})
  OutlinedTextField(phone,{phone=it},label={Text("Phone number")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.Phone,null)})
  OutlinedTextField(address,{address=it},label={Text("Address")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3,leadingIcon={Icon(Icons.Default.LocationOn,null)})
  OutlinedTextField(gst,{gst=it.uppercase().filter{ch->ch.isLetterOrDigit()}.take(15)},label={Text("GSTIN (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Default.ReceiptLong,null)})
 }},confirmButton={Button(enabled=name.isNotBlank()&&name.trim().length<=120,onClick={save(name.trim(),phone.trim(),address.trim(),gst.trim())},shape=RoundedCornerShape(12.dp)){Text(if(initial==null)"Save customer" else "Save changes")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CustomerPicker(customers:List<CustomerEntity>,selected:CustomerEntity?,onSelect:(CustomerEntity?)->Unit){
 var expanded by remember{mutableStateOf(false)}
 ExposedDropdownMenuBox(
  expanded=expanded,
  onExpandedChange={expanded=it},
  modifier=Modifier.fillMaxWidth()
 ){
  OutlinedTextField(
   value=selected?.name.orEmpty(),
   onValueChange={},
   readOnly=true,
   label={Text("Select Customer *")},
   placeholder={Text("Choose customer from list")},
   leadingIcon={
    Icon(
     Icons.Default.Person,
     null,
     tint=if(selected!=null)Blue else Muted,
     modifier=Modifier.size(20.dp)
    )
   },
   trailingIcon={
    ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)
   },
   colors=ExposedDropdownMenuDefaults.outlinedTextFieldColors(
    focusedBorderColor=Blue,
    unfocusedBorderColor=Border
   ),
   shape=RoundedCornerShape(12.dp),
   modifier=Modifier.menuAnchor().fillMaxWidth()
  )
  ExposedDropdownMenu(
   expanded=expanded,
   onDismissRequest={expanded=false},
   modifier=Modifier.fillMaxWidth(0.92f).background(Color.White)
  ){
   if(customers.isEmpty()){
    DropdownMenuItem(
     text={Text("No customers found · Add customer first",fontSize=13.sp,color=Color(0xFFB42318))},
     onClick={expanded=false}
    )
   }else{
    customers.forEach{cust->
     DropdownMenuItem(
      text={
       Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
         Text(cust.name,fontWeight=FontWeight.SemiBold,fontSize=14.sp,color=Navy,modifier=Modifier.weight(1f))
         if(cust.id==selected?.id){
          Icon(Icons.Default.Check,null,tint=Blue,modifier=Modifier.size(16.dp))
         }
        }
        if(cust.phone.isNotBlank()||cust.gstNumber.isNotBlank()){
         Text(
          listOf(cust.phone.ifBlank{null},cust.gstNumber.takeIf{it.isNotBlank()}?.let{"GST: $it"}).filterNotNull().joinToString(" · "),
          fontSize=11.sp,
          color=Muted
         )
        }
       }
      },
      onClick={
       onSelect(cust)
       expanded=false
      },
      contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp)
     )
    }
   }
  }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StaffForm(
 initial: StaffMemberEntity?,
 close: () -> Unit,
 save: (name: String, email: String, password: String, role: String, modules: List<String>) -> Unit
){
 var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
 var email by remember(initial?.id){mutableStateOf(initial?.email.orEmpty())}
 var password by remember(initial?.id){mutableStateOf("")}
 var passwordVisible by remember{mutableStateOf(false)}
 var role by remember(initial?.id){mutableStateOf(initial?.role?: FactoryRole.PRODUCTION_STAFF.name)}
 var roleExpanded by remember{mutableStateOf(false)}

 val allModules = listOf(
  "PRODUCTION" to "Production Entry",
  "PACKING" to "Packing Entry",
  "DISPATCH" to "Dispatch & Deliveries",
  "QUALITY" to "Quality Inspections",
  "PLANNING" to "Production Planning",
  "MACHINES" to "Machines & Maintenance",
  "PRODUCTS" to "Product Catalog & Customers",
  "REPORTS" to "Factory Reports & Intelligence",
  "STAFF" to "Staff Management & Security"
 )

 val initialSelected = remember(initial?.id){
  if(initial!=null&&initial.allowedModules.isNotBlank()){
   initial.allowedModules.split(",").map{it.trim().uppercase()}.filter{it.isNotEmpty()}.toSet()
  }else if(initial==null){
   setOf("PRODUCTION","PACKING")
  }else{
   allModules.map{it.first}.toSet()
  }
 }
 var selectedModules by remember{mutableStateOf(initialSelected)}

 val isNew = initial == null
 val valid = name.trim().length >= 2 &&
   android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() &&
   (!isNew || password.length >= 6)

 AlertDialog(
  onDismissRequest=close,
  title={
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(if(isNew)"Create Staff Account" else "Edit Staff Permissions",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
    Text(if(isNew)"Admin generates Gmail/ID credentials and configures module access." else "Update staff access rights, role and cloud permissions.",fontSize=12.sp,color=Muted)
   }
  },
  text={
   Column(
    Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()).imePadding(),
    verticalArrangement=Arrangement.spacedBy(10.dp)
   ){
    Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){
     Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
      Surface(color=Color.White,shape=RoundedCornerShape(10.dp)){
       Icon(Icons.Default.ManageAccounts,null,tint=Blue,modifier=Modifier.padding(8.dp))
      }
      Column{
       Text(name.trim().ifBlank{if(isNew)"New Staff Account" else "Staff Account"},fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
       Text("Role: ${role.replace("_"," ")} · ${selectedModules.size} modules granted",fontSize=11.sp,color=Muted)
      }
     }
    }

    OutlinedTextField(
     value=name,
     onValueChange={name=it},
     label={Text("Full Name *")},
     singleLine=true,
     leadingIcon={Icon(Icons.Default.Person,null)},
     modifier=Modifier.fillMaxWidth(),
     shape=RoundedCornerShape(12.dp)
    )

    OutlinedTextField(
     value=email,
     onValueChange={email=it},
     label={Text("Staff Email / Gmail *")},
     singleLine=true,
     enabled=isNew,
     keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),
     leadingIcon={Icon(Icons.Default.Email,null)},
     modifier=Modifier.fillMaxWidth(),
     shape=RoundedCornerShape(12.dp)
    )

    if(isNew){
     OutlinedTextField(
      value=password,
      onValueChange={password=it},
      label={Text("Password (min 6 characters) *")},
      singleLine=true,
      visualTransformation=if(passwordVisible)androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
      leadingIcon={Icon(Icons.Default.Lock,null)},
      trailingIcon={
       IconButton(onClick={passwordVisible=!passwordVisible}){
        Icon(if(passwordVisible)Icons.Default.VisibilityOff else Icons.Default.Visibility,null)
       }
      },
      keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
      modifier=Modifier.fillMaxWidth(),
      shape=RoundedCornerShape(12.dp)
     )
    }

    Text("PRIMARY ROLE",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.sp)
    ExposedDropdownMenuBox(
     expanded=roleExpanded,
     onExpandedChange={roleExpanded=it},
     modifier=Modifier.fillMaxWidth()
    ){
     OutlinedTextField(
      value=role.replace("_"," "),
      onValueChange={},
      readOnly=true,
      label={Text("Staff Role")},
      trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=roleExpanded)},
      modifier=Modifier.menuAnchor().fillMaxWidth(),
      shape=RoundedCornerShape(12.dp)
     )
     ExposedDropdownMenu(
      expanded=roleExpanded,
      onDismissRequest={roleExpanded=false}
     ){
      FactoryRole.values().forEach{r->
       DropdownMenuItem(
        text={Text(r.name.replace("_"," "))},
        onClick={
         role=r.name
         roleExpanded=false
         selectedModules=when(r){
          FactoryRole.OWNER, FactoryRole.ADMIN->allModules.map{it.first}.toSet()
          FactoryRole.MANAGER->allModules.filter{it.first!="STAFF"}.map{it.first}.toSet()
          FactoryRole.PRODUCTION_STAFF->setOf("PRODUCTION","PLANNING","MACHINES")
          FactoryRole.PACKING_STAFF->setOf("PACKING")
          FactoryRole.STOREKEEPER->setOf("PRODUCTS","REPORTS")
          FactoryRole.DISPATCH_STAFF->setOf("DISPATCH","PRODUCTS")
          FactoryRole.VIEWER->setOf("REPORTS")
         }
        }
       )
      }
     }
    }

    Row(
     modifier=Modifier.fillMaxWidth().padding(top=4.dp),
     verticalAlignment=Alignment.CenterVertically,
     horizontalArrangement=Arrangement.SpaceBetween
    ){
     Text("CUSTOMIZE MODULE ACCESS",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue,letterSpacing=1.sp)
     Row{
      TextButton(
       onClick={selectedModules=allModules.map{it.first}.toSet()},
       contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)
      ){
       Text("All",fontSize=11.sp,color=Blue)
      }
      TextButton(
       onClick={selectedModules=emptySet()},
       contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)
      ){
       Text("Clear",fontSize=11.sp,color=Color(0xFFB42318))
      }
     }
    }

    Surface(
     color=Page,
     shape=RoundedCornerShape(12.dp),
     border=androidx.compose.foundation.BorderStroke(1.dp,Border)
    ){
     Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
      allModules.forEach{(modKey,modLabel)->
       val checked = modKey in selectedModules || role == "ADMIN"
       Row(
        modifier=Modifier.fillMaxWidth().clickable(enabled=role!="ADMIN"){
         selectedModules=if(modKey in selectedModules)selectedModules-modKey else selectedModules+modKey
        }.padding(vertical=4.dp,horizontal=6.dp),
        verticalAlignment=Alignment.CenterVertically
       ){
        Checkbox(
         checked=checked,
         onCheckedChange={
          if(role!="ADMIN"){
           selectedModules=if(it)selectedModules+modKey else selectedModules-modKey
          }
         },
         enabled=role!="ADMIN"
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)){
         Text(modLabel,fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Navy)
         Text(modKey,fontSize=9.sp,color=Muted)
        }
       }
      }
     }
    }
   }
  },
  confirmButton={
   Button(
    enabled=valid,
    onClick={
     save(name.trim(),email.trim(),password,role,selectedModules.toList())
    },
    shape=RoundedCornerShape(12.dp)
   ){
    Text(if(isNew)"Create Staff" else "Update Access")
   }
  },
  dismissButton={
   TextButton(onClick=close){
    Text("Cancel")
   }
  }
 )
}

@Composable private fun DispatchBatchForm(products:List<ProductEntity>,customers:List<CustomerEntity>,orders:List<SalesOrderEntity>,orderLines:List<SalesOrderLineEntity>,initialOrderId:Long?,close:()->Unit,save:(CustomerEntity?,String,String,String,String,List<DispatchRequestLine>,String,Long?)->Unit){
 var order by remember{mutableStateOf(orders.firstOrNull{it.id==initialOrderId})}
 var customer by remember{mutableStateOf(customers.firstOrNull{it.id==orders.firstOrNull{o->o.id==initialOrderId}?.customerId})}
 var date by remember{mutableStateOf(today())}
 var reference by remember{mutableStateOf("")}
 var remarks by remember{mutableStateOf("")}
 var lines by remember{mutableStateOf(listOf(Triple(products.firstOrNull(), "", "")))}
 var showReview by remember{mutableStateOf(false)}
 val key by remember{mutableStateOf(UUID.randomUUID().toString())}
 val parsed=lines.map{Triple(it.first,it.second.toIntOrNull()?:0,it.third.toIntOrNull()?:0)}
 val valid=customer!=null&&products.isNotEmpty()&&parsed.isNotEmpty()&&parsed.all{(p,packs,loose)->
  val orderValid = if(order==null) true else {
   val productId=p?.id
   val orderId=order!!.id
   val orderProductLines=if(productId==null) emptyList() else orderLines.filter{it.orderId==orderId&&it.productId==productId}
   val remaining=(orderProductLines.sumOf{it.packsOrdered}-orderProductLines.sumOf{it.packsDispatched}).coerceAtLeast(0)
   loose==0&&packs<=remaining
  }
  p!=null&&p.bottlesPerPack>0&&packs>=0&&loose>=0&&(packs>0||loose>0)&&orderValid
 }&&parsed.mapNotNull{it.first?.id}.distinct().size==parsed.size
 val dateValid=runCatching{java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(date)!=null}.getOrDefault(false)&&date.length==10
 val totalPacks=parsed.sumOf{it.second}
 val totalLoose=parsed.sumOf{it.third}
 val totalBottles=parsed.sumOf{(p,packs,loose)->packs.toLong()*(p?.bottlesPerPack?:0)+loose}
 AlertDialog(
  onDismissRequest=close,
  title={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(showReview)"Review dispatch" else "New dispatch",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(if(showReview)"Confirm customer and quantities before posting." else "Dispatch complete packs, loose bottles, or both.",fontSize=12.sp,color=Muted)}},
  text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Surface(color=Color(0xFFFFF0E0),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.LocalShipping,null,tint=Color(0xFFD98523),modifier=Modifier.padding(10.dp))}
    Column{Text(if(showReview)"Final check" else "Dispatch details",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp);Text("Customer · Date · Products",fontSize=11.sp,color=Muted)}
   }
   if(!showReview){
    if(orders.isNotEmpty()){
     Text("LINK TO SALES ORDER",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue)
     orders.forEach{o->FilterChip(selected=order?.id==o.id,onClick={order=o;customer=customers.firstOrNull{it.id==o.customerId};lines=orderLines.filter{it.orderId==o.id}.mapNotNull{line->products.firstOrNull{it.id==line.productId}?.let{Triple(it,"","")}}.ifEmpty{listOf(Triple(products.firstOrNull(),"",""))}},label={Text(o.orderNumber+" · "+o.customerNameSnapshot+" · due "+o.dueDate,fontSize=10.sp)})}
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
       ProductPicker(if(order!=null)products.filter{p->orderLines.any{it.orderId==order!!.id&&it.productId==p.id}} else products,line.first){p->lines=lines.toMutableList().also{it[index]=Triple(p,line.second,line.third)}}
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Field("Complete packs",line.second){qty->lines=lines.toMutableList().also{it[index]=Triple(line.first,qty.filter(Char::isDigit).take(9),line.third)}}}
        Column(Modifier.weight(1f)){Field("Loose bottles",line.third){qty->lines=lines.toMutableList().also{it[index]=Triple(line.first,line.second,qty.filter(Char::isDigit).take(9))}}}
       }
       val packs=line.second.toIntOrNull()?:0
       val loose=line.third.toIntOrNull()?:0
       val packSize=line.first?.bottlesPerPack?:0
       if(line.first!=null&&packSize>0){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
         Text("Bottle equivalent",fontSize=10.sp,color=Muted)
         Text((packs.toLong()*packSize+loose).toString(),fontWeight=FontWeight.Bold,color=Navy,fontSize=12.sp)
        }
        Text("Loose stock must already exist. This dispatch will not silently break a full pack.",fontSize=10.sp,color=Muted)
       }
       if(line.first?.bottlesPerPack?.let{it<=0}==true)Text("Set this product's pack size before dispatch.",fontSize=11.sp,color=Color(0xFF9B5B00))
       if(order!=null&&line.first!=null){val orderProductLines=orderLines.filter{it.orderId==order!!.id&&it.productId==line.first!!.id};val remaining=(orderProductLines.sumOf{it.packsOrdered}-orderProductLines.sumOf{it.packsDispatched}).coerceAtLeast(0);Text("Order remaining: $remaining packs (loose bottles must be zero)",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=if(packs>remaining||loose>0)Color(0xFFB42318) else Blue);if(packs>remaining||loose>0)Text("Sales-order dispatches currently require complete packs only.",fontSize=10.sp,color=Color(0xFFB42318))}
      }
     }
    }
    TextButton(onClick={lines=lines+Triple(products.firstOrNull(),"","")},enabled=products.isNotEmpty()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("Add another product")}
    OutlinedTextField(remarks,{remarks=it},label={Text("Delivery notes (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
   }else{
    Surface(color=Color(0xFFFFF0E0),shape=RoundedCornerShape(14.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text("CUSTOMER",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=1.sp)
      Text(customer?.name.orEmpty(),fontSize=16.sp,fontWeight=FontWeight.Bold,color=Navy)
      Text("Dispatch date  ·  "+date,fontSize=12.sp,color=Muted)
      HorizontalDivider(color=Border)
      Text("ORDER SUMMARY",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=1.sp)
      parsed.forEach{(product,packs,loose)->if(product!=null)Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(product.name,fontWeight=FontWeight.SemiBold,color=Navy,fontSize=12.sp);Text(if(product.bottlesPerPack>0)product.bottlesPerPack.toString()+" bottles per pack" else "Pack size not configured",fontSize=10.sp,color=Muted);Text("$packs packs + $loose loose bottles",fontSize=10.sp,color=Blue)};Text((packs.toLong()*product.bottlesPerPack+loose).toString()+" bottles",fontWeight=FontWeight.Bold,color=Navy)}}
      HorizontalDivider(color=Border)
      Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Complete packs",fontSize=11.sp,color=Muted);Text(totalPacks.toString(),fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Navy)};Column(horizontalAlignment=Alignment.End){Text("Loose · Total bottles",fontSize=11.sp,color=Muted);Text("$totalLoose · $totalBottles",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Color(0xFFD98523))}}
      Text("Stock and permissions are checked before the dispatch is posted.",fontSize=11.sp,color=Muted)
     }
    }
   }
  }},
  confirmButton={if(showReview)Button(enabled=valid&&dateValid,onClick={save(customer,customer?.name.orEmpty(),date,reference,remarks,parsed.map{DispatchRequestLine(it.first!!.id,it.second,it.third)},key,order?.id)},shape=RoundedCornerShape(12.dp)){Text("Confirm & post")}else Button(enabled=valid&&dateValid&&parsed.all{it.first!!.bottlesPerPack>0},onClick={showReview=true},shape=RoundedCornerShape(12.dp)){Text("Review dispatch")}},
  dismissButton={TextButton(onClick={if(showReview)showReview=false else close()}){Text(if(showReview)"Back to edit" else "Cancel")}}
 )
}
@Composable private fun LoginForm(firebaseConfigured:Boolean,close:()->Unit,signIn:(String,String)->Unit){
 var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Factory staff sign-in")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
  Surface(color=if(firebaseConfigured)Color(0xFFE8F7F0) else Color(0xFFFFF2E5),shape=RoundedCornerShape(12.dp)){
   Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Icon(if(firebaseConfigured)Icons.Default.CloudDone else Icons.Default.CloudOff,null,tint=if(firebaseConfigured)Color(0xFF16804A) else Color(0xFF9B5B00))
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
     Text(if(firebaseConfigured)"Server Connected" else "Offline Mode · Local Storage",fontWeight=FontWeight.Bold,color=Navy,fontSize=13.sp)
     Text(if(firebaseConfigured)"Sign in with staff email/ID and password to synchronize with the cloud server." else "App is currently in offline mode. Local records remain safe on this device and upload once connection is restored.",fontSize=11.sp,color=Muted)
    }
   }
  }
  OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,enabled=firebaseConfigured,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email, imeAction=ImeAction.Next))
  OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,enabled=firebaseConfigured,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done))
 }},confirmButton={Button(enabled=firebaseConfigured&&email.isNotBlank()&&password.isNotBlank(),onClick={signIn(email.trim(),password)},shape=RoundedCornerShape(12.dp)){Text("Sign in")}},dismissButton={TextButton(onClick=close){Text("Close")}})
}

@Composable private fun ReverseDispatchForm(close:()->Unit,reverse:(String)->Unit){
 var reason by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Reverse dispatch")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("This records a compensating inventory movement and keeps the original dispatch in the audit history.",fontSize=12.sp,color=Blue);OutlinedTextField(reason,{reason=it},label={Text("Reason for reversal")},modifier=Modifier.fillMaxWidth())}},confirmButton={Button(enabled=reason.isNotBlank(),onClick={reverse(reason.trim())}){Text("Confirm reversal")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable private fun ReverseProductionForm(close:()->Unit,reverse:(String)->Unit){
 var reason by remember{mutableStateOf("")}
 AlertDialog(
  onDismissRequest=close,
  title={Text("Reverse production entry")},
  text={
   Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text(
     "Only reverse if the good bottles from this production entry remain unpacked. This will record compensating stock movements.",
     fontSize=12.sp,
     color=Blue
    )
    OutlinedTextField(
     reason,
     {reason=it},
     label={Text("Reason for reversal")},
     modifier=Modifier.fillMaxWidth()
    )
   }
  },
  confirmButton={
   Button(enabled=reason.isNotBlank(),onClick={reverse(reason.trim())}){
    Text("Confirm reversal")
   }
  },
  dismissButton={TextButton(onClick=close){Text("Cancel")}}
 )
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StockAdjustmentForm(
    products: List<ProductEntity>,
    selectedProduct: ProductEntity?,
    close: () -> Unit,
    submit: (ProductEntity, Int, String, String, String) -> Unit
) {
    var product by remember { mutableStateOf(selectedProduct) }
    var type by remember { mutableStateOf("ADD") }
    var unit by remember { mutableStateOf("PACK") }
    var quantity by remember { mutableStateOf("1") }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Adjust server stock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This adjustment updates authoritative cloud inventory and creates a stock ledger entry.", fontSize = 12.sp, color = Blue)

                if (products.none { it.active }) {
                    Text("No active products available. Create or activate a product first.", color = Color.Red, fontSize = 12.sp)
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = product?.let { "${it.productCode} · ${it.name}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Product") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            products.filter { it.active }.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text("${item.productCode} · ${item.name}") },
                                    onClick = { product = item; expanded = false }
                                )
                            }
                        }
                    }
                }

                Text("Adjustment type", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "ADD", onClick = { type = "ADD" }, label = { Text("Add") })
                    FilterChip(selected = type == "REMOVE", onClick = { type = "REMOVE" }, label = { Text("Remove") })
                }

                Text("Stock unit", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = unit == "PACK", onClick = { unit = "PACK" }, label = { Text("Complete packs") })
                    FilterChip(selected = unit == "BOTTLE", onClick = { unit = "BOTTLE" }, label = { Text("Loose bottles") })
                }

                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter(Char::isDigit).take(9) },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (required)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = product != null && (quantity.toIntOrNull() ?: 0) > 0 && reason.isNotBlank(),
                onClick = { product?.let { submit(it, quantity.toInt(), unit, type, reason.trim()) } }
            ) { Text("Confirm adjustment") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
    )
}

@Composable private fun ReversePackingForm(close:()->Unit,reverse:(String)->Unit){var reason by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text("Reverse packing entry")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Only reverse when the packed stock has not already been dispatched. This creates compensating ledger movements.",fontSize=12.sp,color=Blue);OutlinedTextField(reason,{reason=it},label={Text("Reason for reversal")},modifier=Modifier.fillMaxWidth())}},confirmButton={Button(enabled=reason.isNotBlank(),onClick={reverse(reason.trim())}){Text("Confirm reversal")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}



@Composable
private fun MenuActionCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
 Surface(
  modifier = modifier,
  shape = RoundedCornerShape(12.dp),
  color = color.copy(alpha = 0.1f),
  border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f)),
  onClick = onClick
 ) {
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
   Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Navy, maxLines = 1)
  }
 }
}

@Composable
private fun MenuNavigationRow(
 title: String,
 subtitle: String,
 icon: androidx.compose.ui.graphics.vector.ImageVector,
 accent: Color,
 isSelected: Boolean,
 onClick: () -> Unit
) {
 Surface(
  onClick = onClick,
  color = if (isSelected) Color(0xFFEAF5FC) else Color.White
 ) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
   Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.12f), modifier = Modifier.size(36.dp)) {
    Box(contentAlignment = Alignment.Center) {
     Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
    }
   }
   Column(Modifier.weight(1f)) {
    Text(title, fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold, color = if (isSelected) Blue else Navy, fontSize = 13.sp)
    Text(subtitle, fontSize = 10.sp, color = Muted, maxLines = 1)
   }
   if (isSelected) {
    Surface(shape = RoundedCornerShape(6.dp), color = Blue) {
     Text("ACTIVE", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
   } else {
    Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFBAC7D5), modifier = Modifier.size(18.dp))
   }
  }
 }
}

@Composable
private fun FullSlideMenuScreen(
 isOpen: Boolean,
 onClose: () -> Unit,
 currentTab: PageTab,
 onSelectTab: (PageTab) -> Unit,
 session: FactorySession?,
 onOpenRoleSwitcher: () -> Unit,
 onQuickAction: (String) -> Unit,
 onExportDb: () -> Unit,
 onRestoreDb: () -> Unit,
 onExportJson: () -> Unit,
 onExportCsv: () -> Unit,
 onSyncNow: () -> Unit,
 firebaseConfigured: Boolean,
 onSignOut: () -> Unit
) {
 AnimatedVisibility(
  visible = isOpen,
  enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(200)),
  exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(180))
 ) {
  Surface(
   modifier = Modifier.fillMaxSize(),
   color = Color(0xFFF3F7FC)
  ) {
   Column(Modifier.fillMaxSize()) {
    Surface(
     color = Color(0xFF0B1B31),
     shadowElevation = 6.dp
    ) {
     Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
       Surface(shape = RoundedCornerShape(10.dp), color = Color.White, modifier = Modifier.size(38.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(2.dp)) {
         androidx.compose.foundation.Image(painter = androidx.compose.ui.res.painterResource(R.drawable.tck_brand), contentDescription = "TCK Logo", modifier = Modifier.fillMaxSize())
        }
       }
       Spacer(Modifier.width(10.dp))
       Column(Modifier.weight(1f)) {
        Text("TCK FACTORYFLOW", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 16.sp)
        Text("ALL OPERATIONS & CONTROLS", fontSize = 9.sp, color = Color(0xFF62C9FF), letterSpacing = 1.2.sp)
       }
       IconButton(onClick = onClose, modifier = Modifier.size(36.dp).background(Color(0xFF1E3858), RoundedCornerShape(10.dp))) {
        Icon(Icons.Default.Close, contentDescription = "Close Menu", tint = Color.White, modifier = Modifier.size(20.dp))
       }
      }
      Surface(
       onClick = onOpenRoleSwitcher,
       shape = RoundedCornerShape(12.dp),
       color = Color(0xFF162D4A)
      ) {
       Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).background(if (firebaseConfigured) Color(0xFF36D399) else Color(0xFFFFC857), RoundedCornerShape(5.dp)))
        Column(Modifier.weight(1f)) {
         Text("ACTIVE ROLE: " + (session?.role?.uppercase() ?: "ADMIN"), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
         Text(if (session != null) ((session.email ?: "Local User") + " · Tap to switch") else "Tap to change role or sign in", fontSize = 10.sp, color = Color(0xFFB5CCE5))
        }
        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF294F7C)) {
         Text("SWITCH", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF62C9FF), modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
        }
       }
      }
     }
    }

    LazyColumn(
     modifier = Modifier.weight(1f).fillMaxWidth(),
     contentPadding = PaddingValues(16.dp),
     verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
     item {
      Text("QUICK FACTORY ACTIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue, letterSpacing = 1.4.sp)
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
       MenuActionCard("Add Bottle", Icons.Default.AddCircle, Color(0xFF0F766E), { onClose(); onQuickAction("product") }, Modifier.weight(1f))
       MenuActionCard("Production", Icons.Default.Build, Color(0xFF1D4ED8), { onClose(); onQuickAction("production") }, Modifier.weight(1f))
       MenuActionCard("Packing", Icons.Default.Inventory2, Color(0xFF7E22CE), { onClose(); onQuickAction("packing") }, Modifier.weight(1f))
       MenuActionCard("Dispatch", Icons.Default.LocalShipping, Color(0xFFC2410C), { onClose(); onQuickAction("dispatch") }, Modifier.weight(1f))
      }
     }

     item {
      Text("MANUFACTURING & OPERATIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue, letterSpacing = 1.4.sp)
      Spacer(Modifier.height(8.dp))
      Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
       Column {
        MenuNavigationRow("Factory Overview", "Live production KPIs, shift tracker & summary", Icons.Default.Home, Color(0xFF0284C7), currentTab == PageTab.HOME) { onClose(); onSelectTab(PageTab.HOME) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Bottle Products Catalog", "Manage bottle shapes, photos, icons, and packs", Icons.Default.WaterDrop, Color(0xFF0D9488), currentTab == PageTab.PRODUCTS) { onClose(); onSelectTab(PageTab.PRODUCTS) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Shop Floor Production", "Machine runs, good bottles & rejection tracking", Icons.Default.Build, Color(0xFF2563EB), currentTab == PageTab.PRODUCTION) { onClose(); onSelectTab(PageTab.PRODUCTION) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Packing Line & Bundles", "Finished pack packaging & inventory generation", Icons.Default.Inventory2, Color(0xFF9333EA), currentTab == PageTab.PACKING) { onClose(); onSelectTab(PageTab.PACKING) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Finished Goods Stock", "Physical warehouse stock & finished packs", Icons.Default.Warehouse, Color(0xFF059669), currentTab == PageTab.STOCK) { onClose(); onSelectTab(PageTab.STOCK) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Customer Dispatch", "Deliveries, gate passes & customer orders", Icons.Default.LocalShipping, Color(0xFFD97706), currentTab == PageTab.DISPATCH) { onClose(); onSelectTab(PageTab.DISPATCH) }
       }
      }
     }

     item {
      Text("PLANT & QUALITY MANAGEMENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue, letterSpacing = 1.4.sp)
      Spacer(Modifier.height(8.dp))
      Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
       Column {
        MenuNavigationRow("Machines & Maintenance", "Machine health, downtime tracking & work orders", Icons.Default.Settings, Color(0xFF3B82F6), currentTab == PageTab.MACHINES) { onClose(); onSelectTab(PageTab.MACHINES) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Daily Production Planning", "Shift targets, line allocation & quota tracking", Icons.Default.DateRange, Color(0xFF6366F1), currentTab == PageTab.PLANNING) { onClose(); onSelectTab(PageTab.PLANNING) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Quality Control & Defect Log", "Bottle inspection records, defect severity & checks", Icons.Default.FactCheck, Color(0xFFE11D48), currentTab == PageTab.QUALITY) { onClose(); onSelectTab(PageTab.QUALITY) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Customer Directory", "Customer addresses, GSTIN & contact details", Icons.Default.People, Color(0xFF0891B2), currentTab == PageTab.CUSTOMERS) { onClose(); onSelectTab(PageTab.CUSTOMERS) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Sales Orders", "Order book, due dates & dispatch progress", Icons.Default.ReceiptLong, Color(0xFFEA580C), currentTab == PageTab.ORDERS) { onClose(); onSelectTab(PageTab.ORDERS) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Electricity & Energy", "Meter readings, daily units & power consumption", Icons.Default.Bolt, Color(0xFFCA8A04), currentTab == PageTab.ELECTRICITY) { onClose(); onSelectTab(PageTab.ELECTRICITY) }
       }
      }
     }

     item {
      Text("DATA, CLOUD & GOVERNANCE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue, letterSpacing = 1.4.sp)
      Spacer(Modifier.height(8.dp))
      Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
       Column {
        MenuNavigationRow("Factory Reports & CSV", "Performance statistics & CSV export", Icons.Default.BarChart, Color(0xFF16A34A), currentTab == PageTab.REPORTS) { onClose(); onSelectTab(PageTab.REPORTS) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Inventory Movements Audit", "Immutable ledger of all stock additions and reductions", Icons.Default.History, Color(0xFF475569), currentTab == PageTab.MOVEMENTS) { onClose(); onSelectTab(PageTab.MOVEMENTS) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Security Audit Trail", "Log of admin actions, reversals and updates", Icons.Default.Security, Color(0xFF4338CA), currentTab == PageTab.AUDIT) { onClose(); onSelectTab(PageTab.AUDIT) }
        HorizontalDivider(color = Border)
        MenuNavigationRow("Cloud Sync Outbox", "Cloud sync queue & pending server uploads", Icons.Default.Sync, Color(0xFF0284C7), currentTab == PageTab.SYNC) { onClose(); onSelectTab(PageTab.SYNC) }
       }
      }
     }

     item {
      Surface(
       color = Color(0xFF0F2644),
       shape = RoundedCornerShape(18.dp)
      ) {
       Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
         Icon(Icons.Default.CloudQueue, null, tint = Color(0xFF62C9FF), modifier = Modifier.size(22.dp))
         Column {
          Text("CLOUD & BACKUP TOOLS", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 12.sp)
          Text(if (firebaseConfigured) "Server Connected" else "Offline Mode · Local Storage", fontSize = 10.sp, color = Color(0xFFB9CBE0))
         }
        }
        HorizontalDivider(color = Color(0xFF26456E))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
         Button(
          onClick = { onSyncNow() },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147DEB))
         ) {
          Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Sync Now", fontSize = 11.sp)
         }
         Button(
          onClick = { onClose(); onSelectTab(PageTab.STAFF) },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
         ) {
          Icon(Icons.Default.Badge, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Staff Roles", fontSize = 11.sp)
         }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
         OutlinedButton(
          onClick = { onClose(); onExportDb() },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B6291))
         ) {
          Text("Backup DB", fontSize = 10.sp)
         }
         OutlinedButton(
          onClick = { onClose(); onRestoreDb() },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B6291))
         ) {
          Text("Restore DB", fontSize = 10.sp)
         }
         OutlinedButton(
          onClick = { onClose(); onExportCsv() },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B6291))
         ) {
          Text("CSV Export", fontSize = 10.sp)
         }
        }
        Button(
         onClick = { onClose(); onSignOut() },
         modifier = Modifier.fillMaxWidth(),
         shape = RoundedCornerShape(10.dp),
         colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
         Icon(Icons.Default.Logout, null, modifier = Modifier.size(16.dp))
         Spacer(Modifier.width(6.dp))
         Text("Sign Out / Lock App", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
       }
      }
     }
    }
   }
  }
 }
}

@Composable
private fun StaffAccountDialog(
 onDismiss: () -> Unit,
 firebaseConfigured: Boolean,
 onFirebaseSignIn: (String, String) -> Unit,
 onSyncNow: () -> Unit,
 onSignOut: () -> Unit
) {
 var email by remember { mutableStateOf("") }
 var password by remember { mutableStateOf("") }

 AlertDialog(
  onDismissRequest = onDismiss,
  title = { Text("Staff Account") },
  text = {
   Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
     if (firebaseConfigured)
      "Staff roles and permissions are managed by the factory administrator."
     else
      "Cloud authentication is unavailable. Check the connection and Firebase configuration."
    )
    OutlinedTextField(
     value = email,
     onValueChange = { email = it },
     label = { Text("Staff email") },
     singleLine = true,
     enabled = firebaseConfigured
    )
    OutlinedTextField(
     value = password,
     onValueChange = { password = it },
     label = { Text("Password") },
     singleLine = true,
     enabled = firebaseConfigured,
     visualTransformation = PasswordVisualTransformation()
    )
   }
  },
  confirmButton = {
   TextButton(
    onClick = { onFirebaseSignIn(email.trim(), password) },
    enabled = firebaseConfigured && email.isNotBlank() && password.isNotBlank()
   ) {
    Text("Sign in")
   }
  },
  dismissButton = {
   Row {
    TextButton(onClick = onSyncNow) { Text("Sync") }
    TextButton(onClick = onSignOut) { Text("Sign out") }
    TextButton(onClick = onDismiss) { Text("Close") }
   }
  }
 )
}
