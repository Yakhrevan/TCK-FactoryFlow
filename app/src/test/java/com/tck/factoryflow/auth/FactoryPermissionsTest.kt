package com.tck.factoryflow.auth
import org.junit.Assert.*
import org.junit.Test
class FactoryPermissionsTest {
 @Test fun productionStaffCannotDispatchOrAdjustStock(){assertTrue(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.CREATE_PRODUCTION));assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.POST_DISPATCH));assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.ADJUST_STOCK))}
 @Test fun dispatchStaffCannotManageStaff(){assertTrue(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.POST_DISPATCH));assertFalse(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.MANAGE_STAFF))}
 @Test fun crossOrganisationAccessDenied(){FactoryPermissions.requireOrganisation("factory-a","factory-a");assertThrows(IllegalArgumentException::class.java){FactoryPermissions.requireOrganisation("factory-a","factory-b")}}
 @Test fun firebaseOperatorClaimMapsToProductionRole(){assertEquals(FactoryRole.PRODUCTION_STAFF,FactoryPermissions.fromClaim("operator"))}
 @Test fun roleAliasesMapProperly(){
  assertEquals(FactoryRole.PRODUCTION_STAFF,FactoryPermissions.fromClaim("production"))
  assertEquals(FactoryRole.PRODUCTION_STAFF,FactoryPermissions.fromClaim("production_operator"))
  assertEquals(FactoryRole.PACKING_STAFF,FactoryPermissions.fromClaim("packing"))
  assertEquals(FactoryRole.DISPATCH_STAFF,FactoryPermissions.fromClaim("dispatch"))
  assertEquals(FactoryRole.SUPERVISOR,FactoryPermissions.fromClaim("supervisor"))
  assertEquals(FactoryRole.QUALITY_INSPECTOR,FactoryPermissions.fromClaim("quality_inspector"))
  assertEquals(FactoryRole.QUALITY_INSPECTOR,FactoryPermissions.fromClaim("quality"))
  assertEquals(FactoryRole.STOREKEEPER,FactoryPermissions.fromClaim("inventory_manager"))
 }
 @Test fun supervisorAndQualityPermissions() {
  assertTrue(FactoryPermissions.allows(FactoryRole.SUPERVISOR, FactoryAction.CREATE_PRODUCTION))
  assertTrue(FactoryPermissions.allows(FactoryRole.SUPERVISOR, FactoryAction.MANAGE_MACHINES))
  assertTrue(FactoryPermissions.allows(FactoryRole.SUPERVISOR, FactoryAction.MANAGE_QUALITY))
  assertFalse(FactoryPermissions.allows(FactoryRole.SUPERVISOR, FactoryAction.MANAGE_STAFF))
  assertTrue(FactoryPermissions.allows(FactoryRole.QUALITY_INSPECTOR, FactoryAction.MANAGE_QUALITY))
  assertFalse(FactoryPermissions.allows(FactoryRole.QUALITY_INSPECTOR, FactoryAction.CREATE_PRODUCTION))
  assertFalse(FactoryPermissions.allows(FactoryRole.QUALITY_INSPECTOR, FactoryAction.POST_DISPATCH))
 }
 @Test fun unknownClaimDenied(){assertThrows(IllegalArgumentException::class.java){FactoryPermissions.fromClaim("superuser")}}
 @Test fun blankOrganisationDenied(){assertThrows(IllegalArgumentException::class.java){FactoryPermissions.requireOrganisation("","factory-a")}}

 @Test fun ownerCanManageNewModules() {
  assertTrue(FactoryPermissions.allows(FactoryRole.OWNER,FactoryAction.MANAGE_MACHINES))
  assertTrue(FactoryPermissions.allows(FactoryRole.OWNER,FactoryAction.MANAGE_QUALITY))
  assertTrue(FactoryPermissions.allows(FactoryRole.OWNER,FactoryAction.MANAGE_ORDERS))
  assertTrue(FactoryPermissions.allows(FactoryRole.OWNER,FactoryAction.MANAGE_ELECTRICITY))
 }
 @Test fun productionStaffCannotManageNewSensitiveModules() {
  assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.MANAGE_MACHINES))
  assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.MANAGE_QUALITY))
  assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.MANAGE_ORDERS))
 }
 @Test fun dispatchStaffCanManageOrdersButNotReverseDispatch() {
  assertTrue(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.MANAGE_ORDERS))
  assertFalse(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.REVERSE_DISPATCH))
 }
 @Test fun managerCanManageOperationsButNotStaffAssignments() {
  assertTrue(FactoryPermissions.allows(FactoryRole.MANAGER,FactoryAction.MANAGE_MACHINES))
  assertTrue(FactoryPermissions.allows(FactoryRole.MANAGER,FactoryAction.MANAGE_QUALITY))
  assertFalse(FactoryPermissions.allows(FactoryRole.MANAGER,FactoryAction.MANAGE_STAFF))
 }
 @Test fun organisationMatchingValidation() {
  FactoryPermissions.requireOrganisation("tck-factory-01", "tck-factory-01")
  assertThrows(IllegalArgumentException::class.java) {
   FactoryPermissions.requireOrganisation("tck-factory-01", "other-factory-99")
  }
  assertThrows(IllegalArgumentException::class.java) {
   FactoryPermissions.requireOrganisation("", "tck-factory-01")
  }
  assertThrows(IllegalArgumentException::class.java) {
   FactoryPermissions.requireOrganisation("tck-factory-01", "")
  }
 }
}
