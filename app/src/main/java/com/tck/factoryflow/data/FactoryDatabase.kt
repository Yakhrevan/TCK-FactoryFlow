package com.tck.factoryflow.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="products", indices=[Index(value=["name"], unique=true)])
data class ProductEntity(@PrimaryKey(autoGenerate=true) val id:Long=0, val name:String, val sizeLabel:String, val colour:String="Clear", val bottlesPerPack:Int, val active:Boolean=true, val createdAt:Long=System.currentTimeMillis())

@Entity(tableName="production_entries", indices=[Index("productId"),Index("productionDate")])
data class ProductionEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0, val productId:Long, val productNameSnapshot:String, val productionDate:String, val producedBottles:Int, val goodBottles:Int, val rejectedBottles:Int, val machineName:String="Machine 1", val remarks:String="", val createdAt:Long=System.currentTimeMillis())

@Entity(tableName="packing_entries", indices=[Index("productId"),Index("packingDate")])
data class PackingEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0, val productId:Long, val productNameSnapshot:String, val packingDate:String, val bottlesConsumed:Int, val completePacks:Int, val looseBottles:Int, val createdAt:Long=System.currentTimeMillis())

@Entity(tableName="dispatch_entries", indices=[Index("dispatchDate"),Index("customerName")])
data class DispatchEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0, val dispatchReference:String, val dispatchDate:String, val customerName:String, val productId:Long, val productNameSnapshot:String, val packsDispatched:Int, val remarks:String="", val createdAt:Long=System.currentTimeMillis())

@Entity(tableName="meter_readings", indices=[Index(value=["meterId","readingDate"], unique=true)])
data class MeterReadingEntity(@PrimaryKey(autoGenerate=true) val id:Long=0, val meterId:String="MAIN", val readingDate:String, val startReading:Double, val endReading:Double, val consumption:Double, val remarks:String="", val createdAt:Long=System.currentTimeMillis())

@Dao interface FactoryDao {
 @Query("SELECT * FROM products ORDER BY active DESC, name COLLATE NOCASE") fun observeProducts():Flow<List<ProductEntity>>
 @Query("SELECT * FROM products WHERE active=1 ORDER BY name COLLATE NOCASE") suspend fun activeProducts():List<ProductEntity>
 @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertProduct(p:ProductEntity):Long
 @Query("UPDATE products SET name=:name,sizeLabel=:size,colour=:colour,bottlesPerPack=:pack,active=:active WHERE id=:id") suspend fun updateProduct(id:Long,name:String,size:String,colour:String,pack:Int,active:Boolean)
 @Query("SELECT * FROM production_entries ORDER BY productionDate DESC,createdAt DESC") fun observeProduction():Flow<List<ProductionEntryEntity>>
 @Query("SELECT COALESCE(SUM(goodBottles),0) FROM production_entries WHERE productId=:id") suspend fun totalGoodBottles(id:Long):Int
 @Insert suspend fun insertProduction(e:ProductionEntryEntity):Long
 @Query("SELECT * FROM packing_entries ORDER BY packingDate DESC,createdAt DESC") fun observePacking():Flow<List<PackingEntryEntity>>
 @Query("SELECT COALESCE(SUM(bottlesConsumed),0) FROM packing_entries WHERE productId=:id") suspend fun totalPackedBottles(id:Long):Int
 @Query("SELECT COALESCE(SUM(completePacks),0) FROM packing_entries WHERE productId=:id") suspend fun totalCompletePacks(id:Long):Int
 @Insert suspend fun insertPacking(e:PackingEntryEntity):Long
 @Query("SELECT * FROM dispatch_entries ORDER BY dispatchDate DESC,createdAt DESC") fun observeDispatches():Flow<List<DispatchEntryEntity>>
 @Query("SELECT COALESCE(SUM(packsDispatched),0) FROM dispatch_entries WHERE productId=:id") suspend fun totalDispatchedPacks(id:Long):Int
 @Insert suspend fun insertDispatch(e:DispatchEntryEntity):Long
 @Query("SELECT * FROM meter_readings ORDER BY readingDate DESC") fun observeMeterReadings():Flow<List<MeterReadingEntity>>
 @Insert suspend fun insertMeterReading(e:MeterReadingEntity):Long
}
@Database(entities=[ProductEntity::class,ProductionEntryEntity::class,PackingEntryEntity::class,DispatchEntryEntity::class,MeterReadingEntity::class],version=1,exportSchema=false)
abstract class FactoryDatabase:RoomDatabase(){
 abstract fun factoryDao():FactoryDao
 companion object {
  @Volatile private var instance:FactoryDatabase?=null
  fun get(context:Context):FactoryDatabase = instance ?: synchronized(this){instance ?: Room.databaseBuilder(context.applicationContext,FactoryDatabase::class.java,"factoryflow.db").build().also{instance=it}}
 }
}
