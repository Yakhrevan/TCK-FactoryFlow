package com.tck.factoryflow.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Domain-specific DAOs following Interface Segregation Principle.
 * FactoryDao inherits from these domain interfaces so existing code
 * and tests remain 100% backward-compatible.
 */

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY active DESC, name COLLATE NOCASE")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE active=1 ORDER BY name COLLATE NOCASE")
    suspend fun activeProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id=:id LIMIT 1")
    suspend fun productById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE productCode=:code LIMIT 1")
    suspend fun productByCode(code: String): ProductEntity?

    @Query("SELECT * FROM products WHERE name=:name COLLATE NOCASE LIMIT 1")
    suspend fun productByName(name: String): ProductEntity?

    @Query("UPDATE products SET productCode=:code WHERE id=:id")
    suspend fun updateProductCode(id: Long, code: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProduct(p: ProductEntity): Long

    @Query("UPDATE products SET name=:name,sizeLabel=:size,colour=:colour,bottlesPerPack=:pack,active=:active WHERE id=:id")
    suspend fun updateProduct(id: Long, name: String, size: String, colour: String, pack: Int, active: Boolean)

    @Query("UPDATE products SET name=:name,sizeLabel=:size,colour=:colour,bottlesPerPack=:pack,active=:active,iconName=:iconName,imageUri=:imageUri WHERE id=:id")
    suspend fun updateProductDetails(id: Long, name: String, size: String, colour: String, pack: Int, active: Boolean, iconName: String, imageUri: String)

    @Insert
    suspend fun insertProductHistory(e: ProductConfigHistoryEntity): Long

    @Query("SELECT * FROM product_config_history WHERE productId=:id ORDER BY changedAt DESC")
    fun observeProductHistory(id: Long): Flow<List<ProductConfigHistoryEntity>>

    @Query("DELETE FROM products WHERE id=:id")
    suspend fun deleteProduct(id: Long): Int
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE active=1 ORDER BY name COLLATE NOCASE")
    fun observeCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE active=1 ORDER BY name COLLATE NOCASE")
    suspend fun activeCustomers(): List<CustomerEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(c: CustomerEntity): Long

    @Query("UPDATE customers SET name=:name,phone=:phone,address=:address,gstNumber=:gst,active=:active WHERE id=:id")
    suspend fun updateCustomer(id: Long, name: String, phone: String, address: String, gst: String, active: Boolean)
}

@Dao
interface ElectricityDao {
    @Query("SELECT * FROM meter_readings WHERE id=:id LIMIT 1")
    suspend fun meterById(id: Long): MeterReadingEntity?

    @Query("UPDATE meter_readings SET remarks=remarks WHERE id=:id")
    suspend fun touchMeter(id: Long)

    @Query("SELECT * FROM meter_readings ORDER BY readingDate DESC, createdAt DESC")
    fun observeMeterReadings(): Flow<List<MeterReadingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeterReading(e: MeterReadingEntity): Long

    @Update
    suspend fun updateMeterReading(e: MeterReadingEntity)

    @Query("SELECT * FROM meter_readings WHERE meterId=:meterId AND readingDate=:readingDate LIMIT 1")
    suspend fun meterReadingByDate(meterId: String, readingDate: String): MeterReadingEntity?

    @Query("DELETE FROM meter_readings WHERE id=:id")
    suspend fun deleteMeterReading(id: Long)
}

@Dao
interface StaffDao {
    @Query("SELECT * FROM staff_members ORDER BY name COLLATE NOCASE")
    fun observeStaffMembers(): Flow<List<StaffMemberEntity>>

    @Query("SELECT * FROM staff_members WHERE email=:email LIMIT 1")
    suspend fun staffByEmail(email: String): StaffMemberEntity?

    @Query("SELECT * FROM staff_members WHERE id=:id LIMIT 1")
    suspend fun staffById(id: Long): StaffMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaffMember(staff: StaffMemberEntity): Long

    @Query("UPDATE staff_members SET name=:name,role=:role,allowedModules=:allowedModules,active=:active,updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateStaffMember(id: Long, name: String, role: String, allowedModules: String, active: Boolean, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE staff_members SET active=:active,updatedAt=:updatedAt WHERE id=:id")
    suspend fun setStaffMemberActive(id: Long, active: Boolean, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM staff_members WHERE id=:id")
    suspend fun deleteStaffMember(id: Long): Int
}
