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

private val Blue=Color(0xFF0756A4); private val Cyan=Color(0xFF13A7D8); private val Navy=Color(0xFF102B4E); private val Page=Color(0xFFF1F7FC); private val Muted=Color(0xFF6B7E93); private val Border=Color(0xFFDCE7F1)
private fun today()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
class MainActivity:ComponentActivity(){
 private val db by lazy{FactoryDatabase.get(this)}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);lifecycleScope.launch{if(db.factoryDao().activeProducts().isEmpty()) listOf(ProductEntity(name="300ml",sizeLabel="300ml",bottlesPerPack=0),ProductEntity(name="500ml",sizeLabel="500ml",bottlesPerPack=0),ProductEntity(name="1 Ltr",sizeLabel="1 litre",bottlesPerPack=0),ProductEntity(name="200ml Green",sizeLabel="200ml",colour="Green",bottlesPerPack=0),ProductEntity(name="200ml Milk White",sizeLabel="200ml",colour="Milk White",bottlesPerPack=0),ProductEntity(name="200ml Clear",sizeLabel="200ml",bottlesPerPack=0)).forEach{runCatching{db.factoryDao().insertProduct(it)}}};if(FirebaseApp.getApps(this).isNotEmpty()) SyncScheduler.schedule(this);setContent{FactoryApp(db.factoryDao())}}
}
private enum class PageTab{HOME,PRODUCTS,PRODUCTION,PACKING,STOCK,DISPATCH,ELECTRICITY,REPORTS,CUSTOMERS}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FactoryApp(dao:FactoryDao){
 var tab by remember{mutableStateOf(PageTab.HOME)}; var dialog by remember{mutableStateOf("")}; var session by remember{mutableStateOf<FactorySession?>(null)}; var editingProduct by remember{mutableStateOf<ProductEntity?>(null)}; var reversalBatchId by remember{mutableStateOf<Long?>(null)}; var reversalPackingId by remember{mutableStateOf<Long?>(null)}; var message by remember{mutableStateOf<String?>(null)}; var reportFrom by remember{mutableStateOf(today().substring(0,7)+"-01")}; var reportTo by remember{mutableStateOf(today())}; var productSearch by remember{mutableStateOf("")}; var customerSearch by remember{mutableStateOf("")}; var dispatchSearch by remember{mutableStateOf("")}; val scope=rememberCoroutineScope()
 val products by dao.observeProducts().collectAsStateWithLifecycle(emptyList()); val production by dao.observeProduction().collectAsStateWithLifecycle(emptyList()); val packing by dao.observePacking().collectAsStateWithLifecycle(emptyList()); val dispatches by dao.observeDispatches().collectAsStateWithLifecycle(emptyList()); val dispatchBatches by dao.observeDispatchBatches().collectAsStateWithLifecycle(emptyList()); val dispatchLines by dao.observeDispatchLines().collectAsStateWithLifecycle(emptyList()); val meters by dao.observeMeterReadings().collectAsStateWithLifecycle(emptyList()); val customers by dao.observeCustomers().collectAsStateWithLifecycle(emptyList()); val active=products.filter{it.active}; val date=today()
 val context=LocalContext.current
 LaunchedEffect(context){session=runCatching{FactoryAuthRepository(context).currentSession()}.getOrNull()}
 val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
  if(uri!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ReportExport.csv(production,packing,dispatches,meters,dispatchBatches,dispatchLines)) } ?: error("Could not open export destination") }.onSuccess { message="CSV report exported" }.onFailure { message="Export failed: "+it.message }
 }
 MaterialTheme(colorScheme=lightColorScheme(primary=Blue,secondary=Cyan,background=Page,surface=Color.White,outline=Border)){
 Scaffold(topBar={TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=RoundedCornerShape(12.dp),color=Navy,modifier=Modifier.size(42.dp)){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Inventory2,null,tint=Color.White,modifier=Modifier.size(25.dp))}};Column{Text("TCK FactoryFlow",fontWeight=FontWeight.ExtraBold,color=Navy,fontSize=18.sp);Text("MANUFACTURING OPERATIONS",fontSize=9.sp,color=Muted,letterSpacing=1.2.sp)} }},actions={TextButton(onClick={dialog="login"}){Column(horizontalAlignment=Alignment.End){Text(session?.let{it.role.uppercase()}?:"SIGN IN",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Blue);Text(if(session!=null)"ACCOUNT" else "LOCAL MODE",fontSize=8.sp,color=Muted)}}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.White))},bottomBar={NavigationBar(containerColor=Color.White,tonalElevation=8.dp){listOf(PageTab.HOME,PageTab.PRODUCTION,PageTab.STOCK,PageTab.REPORTS,PageTab.PRODUCTS).forEach{t->NavigationBarItem(selected=tab==t,onClick={tab=t},icon={Icon(when(t){PageTab.HOME->Icons.Default.Home;PageTab.PRODUCTION->Icons.Default.Build;PageTab.STOCK->Icons.Default.Inventory2;PageTab.REPORTS->Icons.Default.BarChart;else->Icons.Default.WaterDrop},null)},label={Text(t.name.lowercase().replaceFirstChar{it.uppercase()},fontSize=10.sp,fontWeight=if(tab==t)FontWeight.Bold else FontWeight.Normal)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Navy,selectedTextColor=Navy,indicatorColor=Color(0xFFE7DDF6),unselectedIconColor=Muted,unselectedTextColor=Muted))}}},containerColor=Page){padding->
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
  val batchOut=dispatchLines.filter{line->line.batchId in dispatchBatches.filter{it.status=="POSTED"}.map{it.id}}.groupBy{it.productId}.mapValues{(_,entries)->entries.sumOf{it.packs}}
  val lowStock=active.filter{p->(packedAll[p.id]?:0)-(legacyOut[p.id]?:0)-(batchOut[p.id]?:0)<=5}
  LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=18.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   item{Surface(color=Navy,shape=RoundedCornerShape(24.dp)){Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("FACTORY OVERVIEW",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.6.sp);Text("Good "+java.text.SimpleDateFormat("dd MMM yyyy",Locale.US).format(Date()),fontSize=24.sp,fontWeight=FontWeight.ExtraBold,color=Color.White);Text("Here is today's operation summary.",fontSize=12.sp,color=Color(0xFFD4E2F0))};Surface(color=Color(0xFF24476C),shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Inventory2,null,tint=Color.White,modifier=Modifier.padding(13.dp).size(26.dp))}}
    HorizontalDivider(color=Color(0xFF345574))
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(Icons.Default.Circle,contentDescription=null,tint=if(session!=null)Color(0xFF4ADE80) else Gold,modifier=Modifier.size(9.dp));Text(if(session!=null)"Signed in · "+session!!.role else "Local device mode · Sign in for staff access",fontSize=11.sp,color=Color(0xFFE1ECF7))}
   }}}
   item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Today's performance",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Live totals from saved factory records",fontSize=11.sp,color=Muted)};TextButton(onClick={tab=PageTab.REPORTS}){Text("View reports")}}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){DashboardMetricCard("Bottles produced",produced.toString(),"Today",Modifier.weight(1f),Icons.Default.WaterDrop);DashboardMetricCard("Good bottles",good.toString(),"Accepted output",Modifier.weight(1f),Icons.Default.CheckCircle)}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){DashboardMetricCard("Rejected bottles",rejected.toString(),String.format(Locale.US,"%.1f%% rejection",rejectionRate),Modifier.weight(1f),Icons.Default.WarningAmber);DashboardMetricCard("Packs completed",packed.toString(),"Today",Modifier.weight(1f),Icons.Default.Inventory2)}}
   item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){DashboardMetricCard("Packs dispatched",dispatched.toString(),"Today's deliveries",Modifier.weight(1f),Icons.Default.LocalShipping);DashboardMetricCard("Electricity",String.format(Locale.US,"%.1f",todayMeters.sumOf{it.consumption}),"Units today",Modifier.weight(1f),Icons.Default.Bolt)}}
   item{Surface(color=Color.White,shape=RoundedCornerShape(20.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Production quality",fontWeight=FontWeight.Bold,color=Navy,fontSize=16.sp);Text("Good output vs rejects",fontSize=11.sp,color=Muted)};Text(String.format(Locale.US,"%.1f%% good",if(produced>0)good*100.0/produced else 0.0),fontWeight=FontWeight.Bold,color=Blue,fontSize=12.sp)}
    LinearProgressIndicator(progress={if(produced>0)(good.toFloat()/produced).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(8.dp),color=Color(0xFF20A779),trackColor=Color(0xFFE8EFF5),strokeCap=androidx.compose.ui.graphics.StrokeCap.Round)
    Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text(good.toString()+" good bottles",fontSize=11.sp,color=Muted);Text(rejected.toString()+" rejected",fontSize=11.sp,color=Muted)}
   }}}
   item{Row(verticalAlignment=Alignment.CenterVertically){Text("Quick operations",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Tap to create",fontSize=10.sp,color=Muted)}}
   item{Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Action("Production",Icons.Default.Build,{dialog="production"},Modifier.weight(1f));Action("Packing",Icons.Default.Inventory2,{dialog="packing"},Modifier.weight(1f));Action("Dispatch",Icons.Default.LocalShipping,{dialog="dispatch"},Modifier.weight(1f))};Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Action("Products",Icons.Default.WaterDrop,{tab=PageTab.PRODUCTS},Modifier.weight(1f));Action("Electricity",Icons.Default.Bolt,{dialog="meter"},Modifier.weight(1f));Action("Customers",Icons.Default.People,{tab=PageTab.CUSTOMERS},Modifier.weight(1f))}}}
   item{Surface(color=if(lowStock.isEmpty())Color(0xFFE8F7F0) else Color(0xFFFFF4DF),shape=RoundedCornerShape(20.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Icon(if(lowStock.isEmpty())Icons.Default.CheckCircle else Icons.Default.WarningAmber,null,tint=if(lowStock.isEmpty())Color(0xFF16804A) else Color(0xFF9B5B00));Column(Modifier.weight(1f).padding(start=8.dp)){Text(if(lowStock.isEmpty())"Stock check" else "Stock needs attention",fontWeight=FontWeight.Bold,color=Navy);Text(if(lowStock.isEmpty())"No products at or below the 5-pack alert threshold." else lowStock.size.toString()+" product(s) have 5 or fewer available packs.",fontSize=11.sp,color=Muted)};TextButton(onClick={tab=PageTab.STOCK}){Text("Open stock")}}
    if(lowStock.isNotEmpty())lowStock.take(3).forEach{p->Text(p.name+" · "+((packedAll[p.id]?:0)-(legacyOut[p.id]?:0)-(batchOut[p.id]?:0)).coerceAtLeast(0)+" packs",fontSize=12.sp,color=Navy)}
   }}}
   item{Row(verticalAlignment=Alignment.CenterVertically){Text("Product status",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy);TextButton(onClick={tab=PageTab.PRODUCTS}){Text("Manage")}}}
   items(active.take(6),key={it.id}){p->BottleProductCard(name=p.name,capacity=p.sizeLabel,packSizeLabel=if(p.bottlesPerPack>0)p.bottlesPerPack.toString()+" bottles per pack" else "Pack size not configured",active=p.active,bottleType=p.colour,onClick={editingProduct=p;dialog="product"})}
   item{Row(verticalAlignment=Alignment.CenterVertically){Text("Recent production",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy);TextButton(onClick={tab=PageTab.PRODUCTION}){Text("View all")}}}
   if(production.isEmpty())item{EmptyDashboardState("No production entries yet","Record the first production run to populate your dashboard",Icons.Default.Build)}
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
   item{Text("Production history",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   if(production.isEmpty())item{EmptyDashboardState("No production recorded","Create a production run to start tracking output.",Icons.Default.Build)}
   items(production,key={it.id}){entry->
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Surface(color=Color(0xFFEAF5FC),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Build,null,tint=Blue,modifier=Modifier.padding(10.dp).size(22.dp))};Column(Modifier.weight(1f).padding(start=10.dp)){Text(entry.productNameSnapshot,fontWeight=FontWeight.Bold,color=Navy);Text(entry.productionDate+" · "+entry.machineName,fontSize=11.sp,color=Muted)};Text("SAVED",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Color(0xFF16804A))}
      HorizontalDivider(color=Border)
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StockMiniMetric("Produced",entry.producedBottles,Modifier.weight(1f));StockMiniMetric("Good",entry.goodBottles,Modifier.weight(1f));StockMiniMetric("Rejected",entry.rejectedBottles,Modifier.weight(1f))}
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
      Row(verticalAlignment=Alignment.CenterVertically){Text(batch.dispatchDate+" · "+if(batch.remarks.isBlank())"No remarks" else batch.remarks,Modifier.weight(1f),fontSize=10.sp,color=Muted);if(batch.status=="POSTED"&&session?.role in setOf("owner","admin","manager"))TextButton(onClick={reversalBatchId=batch.id;dialog="reverse"}){Icon(Icons.Default.Undo,null);Text("Reverse")}}
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
 PageTab.CUSTOMERS->Column(Modifier.fillMaxSize().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Customers",Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Bold,color=Navy);Button(onClick={dialog="customer"}){Text("+ Add")}};LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(customers){customer->Record(customer.name,customer.phone.ifBlank{"No phone"},customer.address.ifBlank{"No address"})}}}
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
   item{Text("Stock by product",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Navy)}
   items(active,key={it.id}){p->
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
 PageTab.REPORTS->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 item{Text("Reports & Analytics",fontSize=23.sp,fontWeight=FontWeight.Bold,color=Navy);Text("Saved records · daily / weekly / monthly / custom range",fontSize=12.sp,color=Blue);Button(onClick={exportLauncher.launch("factoryflow-report-"+date+".csv")},modifier=Modifier.fillMaxWidth()){Text("Export CSV / Backup")}}
 item{OutlinedTextField(reportFrom,{reportFrom=it},label={Text("From date (yyyy-MM-dd)")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(reportTo,{reportTo=it},label={Text("To date (yyyy-MM-dd)")},singleLine=true,modifier=Modifier.fillMaxWidth())}
 item{val totals=runCatching{ReportExport.totals(java.time.LocalDate.parse(reportFrom),java.time.LocalDate.parse(reportTo),production,packing,dispatches,meters,dispatchBatches,dispatchLines)}.getOrNull();if(totals==null)Text("Enter valid dates in yyyy-MM-dd format; end date must not precede start date.",color=Color.Red) else Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Selected period",fontWeight=FontWeight.Bold,color=Navy);Metric("Produced bottles",totals.produced.toString(),Modifier.fillMaxWidth());Metric("Good / rejected",totals.good.toString()+" / "+totals.rejected,Modifier.fillMaxWidth());Metric("Packed bottles",totals.packedBottles.toString(),Modifier.fillMaxWidth());Metric("Complete packs",totals.completePacks.toString(),Modifier.fillMaxWidth());Metric("Dispatched packs",totals.dispatchedPacks.toString(),Modifier.fillMaxWidth());Metric("Electricity",totals.electricity.toString()+" units",Modifier.fillMaxWidth())}}
 item{val comparison=runCatching{val from=java.time.LocalDate.parse(reportFrom);val to=java.time.LocalDate.parse(reportTo);val days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;val priorTo=from.minusDays(1);val priorFrom=priorTo.minusDays(days-1);ReportExport.totals(priorFrom,priorTo,production,packing,dispatches,meters,dispatchBatches,dispatchLines)}.getOrNull();if(comparison!=null){Text("Previous equal-length period",fontWeight=FontWeight.Bold,color=Navy);Text("Produced: "+comparison.produced+" · Packed: "+comparison.completePacks+" packs · Dispatched: "+comparison.dispatchedPacks+" packs · Electricity: "+comparison.electricity+" units",fontSize=12.sp,color=Blue)}}
}
 }
 message?.let{Text(it,Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color.White,RoundedCornerShape(12.dp)).padding(12.dp),color=Blue)}
 }
 }
 }
 if(dialog=="reverse"&&reversalBatchId!=null)ReverseDispatchForm({dialog="";reversalBatchId=null}){reason->scope.launch{runCatching{dao.reverseDispatch(reversalBatchId!!,reason,session?.uid?:"local",session?.organisationId?:"local-org");"Dispatch reversed"}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalBatchId=null}
 if(dialog=="reversePacking"&&reversalPackingId!=null)ReversePackingForm({dialog="";reversalPackingId=null}){reason->scope.launch{runCatching{dao.reversePacking(reversalPackingId!!,reason,session?.uid?: "local",session?.organisationId?: "local-org");"Packing entry reversed"}.onSuccess{message=it}.onFailure{message=it.message}};dialog="";reversalPackingId=null}

 if(dialog=="login")LoginForm({dialog=""}){email,password->scope.launch{runCatching{FactoryAuthRepository(context).signIn(email,password)}.onSuccess{session=it;message="Signed in as "+it.role+" · "+it.organisationId}.onFailure{message=it.message}};dialog=""}
 if(dialog=="product")ProductForm(editingProduct,{dialog="";editingProduct=null}){n,s,c,p->val existing=editingProduct;scope.launch{runCatching{if(existing==null)dao.insertProduct(ProductEntity(name=n,sizeLabel=s,colour=c,bottlesPerPack=p)) else dao.updateProductWithHistory(existing.id,n,s,c,p,existing.active,session?.uid?:"local","Product configuration edited",session?.organisationId?:"local-org")}.onSuccess{message="Product saved with configuration history"}.onFailure{message=it.message}};dialog="";editingProduct=null}
 if(dialog=="production")ProductionForm(active,{dialog=""}){p,d,total,good,rejected,machine,remarks->scope.launch{runCatching{ProductionQuantities(total,good,rejected).validate();require(p!=null){"Choose a product"};dao.postProduction(ProductionEntryEntity(productId=p.id,productNameSnapshot=p.name,productionDate=d,producedBottles=total,goodBottles=good,rejectedBottles=rejected,machineName=machine,remarks=remarks),session?.organisationId?:"local-org",session?.uid?:"local")}.onSuccess{message="Production saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="packing")PackingForm(active,dao,{dialog=""}){p,d,n->scope.launch{runCatching{require(p!=null){"Choose a product"};val calc=PackCalculation.calculate(n,p.bottlesPerPack);dao.postPacking(PackingEntryEntity(productId=p.id,productNameSnapshot=p.name,packingDate=d,bottlesConsumed=n,completePacks=calc.completePacks,looseBottles=calc.looseBottles),session?.organisationId?:"local-org",session?.uid?:"local");"Saved "+calc.completePacks+" packs and "+calc.looseBottles+" loose bottles"}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
 if(dialog=="dispatch")DispatchBatchForm(active,customers,{dialog=""}){customer,customerName,d,reference,remarks,lines,key->scope.launch{runCatching{dao.postMultiLineDispatch(DispatchBatchEntity(idempotencyKey=key,dispatchReference=reference.ifBlank{"DSP-"+System.currentTimeMillis()},dispatchDate=d,customerId=customer?.id,customerNameSnapshot=customerName,remarks=remarks,createdBy=session?.uid?:"local"),lines,session?.organisationId?:"local-org",session?.uid?:"local");"Dispatch saved"}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
 if(dialog=="customer")CustomerForm({dialog=""}){name,phone,address,gst->scope.launch{runCatching{dao.insertCustomer(CustomerEntity(name=name,phone=phone,address=address,gstNumber=gst))}.onSuccess{message="Customer saved"}.onFailure{message=it.message}};dialog=""}
 if(dialog=="meter")MeterForm({dialog=""}){d,m,s,e,r->scope.launch{runCatching{val used=ElectricityRules.consumption(s,e);dao.insertMeterReading(MeterReadingEntity(meterId=m,readingDate=d,startReading=s,endReading=e,consumption=used,remarks=r));"Reading saved: "+used+" units"}.onSuccess{message=it}.onFailure{message=it.message}};dialog=""}
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
@Composable private fun ProductForm(initial:ProductEntity?,close:()->Unit,save:(String,String,String,Int)->Unit){var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())};var size by remember(initial?.id){mutableStateOf(initial?.sizeLabel.orEmpty())};var colour by remember(initial?.id){mutableStateOf(initial?.colour?:"Clear")};var pack by remember(initial?.id){mutableStateOf((initial?.bottlesPerPack?:0).let{if(it>0)it.toString() else ""})};AlertDialog(onDismissRequest=close,title={Text(if(initial==null)"Add product" else "Edit product")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(name,{name=it},label={Text("Product name")});OutlinedTextField(size,{size=it},label={Text("Size / type")});OutlinedTextField(colour,{colour=it},label={Text("Colour")});Field("Bottles per pack",pack,{pack=it.filter(Char::isDigit)})}},confirmButton={Button(enabled=name.isNotBlank()&&size.isNotBlank()&&(pack.toIntOrNull()?:-1)>=0,onClick={save(name.trim(),size.trim(),colour.trim(),pack.toIntOrNull()?:0)}){Text("Save")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun ProductionForm(products:List<ProductEntity>,close:()->Unit,save:(ProductEntity?,String,Int,Int,Int,String,String)->Unit){var p by remember{mutableStateOf(products.firstOrNull())};var d by remember{mutableStateOf(today())};var total by remember{mutableStateOf("")};var good by remember{mutableStateOf("")};var rejected by remember{mutableStateOf("0")};var machine by remember{mutableStateOf("Machine 1")};var remarks by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text("Production entry")},text={Column(verticalArrangement=Arrangement.spacedBy(5.dp)){ProductPicker(products,p){p=it};p?.let{BottleProductCard(name=it.name,capacity=it.sizeLabel,packSizeLabel=if(it.bottlesPerPack>0) "${it.bottlesPerPack} bottles per pack" else "Pack size not configured",active=it.active,bottleType=it.colour)};DateField(d){d=it};Field("Produced bottles",total){total=it};Field("Good bottles",good){good=it};Field("Rejected bottles",rejected){rejected=it};OutlinedTextField(machine,{machine=it},label={Text("Machine")});OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")})}},confirmButton={Button(enabled=(total.toIntOrNull()?:-1)>=0&&(good.toIntOrNull()?:-1)>=0&&(rejected.toIntOrNull()?:-1)>=0,onClick={val a=total.toIntOrNull();val b=good.toIntOrNull();val c=rejected.toIntOrNull();if(a!=null&&b!=null&&c!=null)save(p,d,a,b,c,machine,remarks)}){Text("Save")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun PackingForm(products:List<ProductEntity>,dao:FactoryDao,close:()->Unit,save:(ProductEntity?,String,Int)->Unit){var p by remember{mutableStateOf(products.firstOrNull())};var d by remember{mutableStateOf(today())};var bottles by remember{mutableStateOf("")};var available by remember{mutableIntStateOf(0)};LaunchedEffect(p?.id){p?.let{available=dao.totalGoodBottles(it.id)-dao.totalPackedBottles(it.id)}};val n=bottles.toIntOrNull()?:0;val calc=runCatching{PackCalculation.calculate(n,p?.bottlesPerPack?:0)}.getOrNull();AlertDialog(onDismissRequest=close,title={Text("Packing / finishing")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){ProductPicker(products,p){p=it};DateField(d){d=it};Text("Available unpacked bottles: "+available.coerceAtLeast(0),color=Blue);Field("Bottles to pack",bottles){bottles=it};PackCalculationCard(eligibleBottles=n,bottlesPerPack=p?.bottlesPerPack?.takeIf{it>0})}},confirmButton={Button(enabled=n>0&&n<=available&&p!=null&&p?.bottlesPerPack?.let{it>0}==true,onClick={save(p,d,n)}){Text("Save packing")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun DispatchForm(products:List<ProductEntity>,dao:FactoryDao,close:()->Unit,save:(ProductEntity?,String,String,Int,String,String)->Unit){var p by remember{mutableStateOf(products.firstOrNull())};var d by remember{mutableStateOf(today())};var customer by remember{mutableStateOf("Nachiyar Cooldrinks")};var packs by remember{mutableStateOf("")};var ref by remember{mutableStateOf("")};var remarks by remember{mutableStateOf("")};var stock by remember{mutableIntStateOf(0)};LaunchedEffect(p?.id){p?.let{stock=dao.totalCompletePacks(it.id)-dao.totalDispatchedPacks(it.id)}};val n=packs.toIntOrNull()?:0;AlertDialog(onDismissRequest=close,title={Text("Delivery / dispatch")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){DateField(d){d=it};OutlinedTextField(customer,{customer=it},label={Text("Customer")});ProductPicker(products,p){p=it};Text("Available finished packs: "+stock.coerceAtLeast(0),color=Blue);Field("Packs to dispatch",packs){packs=it};OutlinedTextField(ref,{ref=it},label={Text("Delivery reference")});OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")})}},confirmButton={Button(enabled=n>0&&n<=stock&&p!=null&&customer.isNotBlank(),onClick={save(p,d,customer,n,ref,remarks)}){Text("Confirm dispatch")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun MeterForm(close:()->Unit,save:(String,String,Double,Double,String)->Unit){var d by remember{mutableStateOf(today())};var meter by remember{mutableStateOf("MAIN")};var start by remember{mutableStateOf("")};var end by remember{mutableStateOf("")};var remarks by remember{mutableStateOf("")};val used=runCatching{ElectricityRules.consumption(start.toDouble(),end.toDouble())}.getOrNull();AlertDialog(onDismissRequest=close,title={Text("Electricity reading")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){DateField(d){d=it};OutlinedTextField(meter,{meter=it},label={Text("Meter ID")});Field("Start reading",start,{start=it},true);Field("End reading",end,{end=it},true);Text("Consumption: "+(used?.toString()?: "—")+" units",color=Blue);OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")})}},confirmButton={Button(enabled=used!=null,onClick={if(used!=null)save(d,meter,start.toDouble(),end.toDouble(),remarks)}){Text("Save reading")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable private fun CustomerForm(close:()->Unit,save:(String,String,String,String)->Unit){
 var name by remember{mutableStateOf("")};var phone by remember{mutableStateOf("")};var address by remember{mutableStateOf("")};var gst by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Add customer")},text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){OutlinedTextField(name,{name=it},label={Text("Customer name")},singleLine=true);OutlinedTextField(phone,{phone=it},label={Text("Phone")},singleLine=true);OutlinedTextField(address,{address=it},label={Text("Address")});OutlinedTextField(gst,{gst=it},label={Text("GST number")},singleLine=true)}},confirmButton={Button(enabled=name.isNotBlank(),onClick={save(name.trim(),phone.trim(),address.trim(),gst.trim())}){Text("Save customer")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}
@Composable private fun CustomerPicker(customers:List<CustomerEntity>,selected:CustomerEntity?,onSelect:(CustomerEntity?)->Unit){
 var expanded by remember{mutableStateOf(false)}
 Box { OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(selected?.name?:"Select customer")}
  DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){customers.forEach{customer->DropdownMenuItem(text={Text(customer.name)},onClick={onSelect(customer).let{expanded=false}})}}
 }
}
@Composable private fun DispatchBatchForm(products:List<ProductEntity>,customers:List<CustomerEntity>,close:()->Unit,save:(CustomerEntity?,String,String,String,String,List<DispatchRequestLine>,String)->Unit){
 var customer by remember{mutableStateOf(customers.firstOrNull())}
 var date by remember{mutableStateOf(today())};var reference by remember{mutableStateOf("")};var remarks by remember{mutableStateOf("")}
 var lines by remember{mutableStateOf(listOf(products.firstOrNull() to ""))}
 val key by remember{mutableStateOf(UUID.randomUUID().toString())}
 val parsed=lines.map{it.first to (it.second.toIntOrNull()?:0)}
 val valid=customer!=null&&products.isNotEmpty()&&parsed.isNotEmpty()&&parsed.all{it.first!=null&&it.second>0}
 AlertDialog(onDismissRequest=close,title={Text("Multi-product dispatch")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  CustomerPicker(customers,customer){customer=it}
  DateField(date){date=it}
  OutlinedTextField(reference,{reference=it},label={Text("Dispatch / delivery reference")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Text("Dispatch items",fontWeight=FontWeight.Bold,color=Navy)
  lines.forEachIndexed{index,line->
   Surface(color=Page,shape=RoundedCornerShape(10.dp)){Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
    ProductPicker(products,line.first){p->lines=lines.toMutableList().also{it[index]=p to line.second}}
    Field("Packs",line.second){qty->lines=lines.toMutableList().also{it[index]=line.first to qty.filter(Char::isDigit)}}
    if(lines.size>1)TextButton(onClick={lines=lines.filterIndexed{idx,_->idx!=index}}){Text("Remove item")}
   }}
  }
  TextButton(onClick={lines=lines+ (products.firstOrNull() to "")},enabled=products.isNotEmpty()){Text("+ Add product line")}
  OutlinedTextField(remarks,{remarks=it},label={Text("Remarks")},modifier=Modifier.fillMaxWidth())
  if(customers.isEmpty())Text("Add a customer first in the Customers section.",color=Color.Red,fontSize=12.sp)
 }},confirmButton={Button(enabled=valid,onClick={save(customer,customer?.name.orEmpty(),date,reference,remarks,parsed.map{DispatchRequestLine(it.first!!.id,it.second)},key)}){Text("Post dispatch")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
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
