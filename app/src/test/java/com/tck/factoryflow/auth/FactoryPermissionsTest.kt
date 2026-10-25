package com.tck.factoryflow.auth
import org.junit.Assert.*
import org.junit.Test
class FactoryPermissionsTest {
 @Test fun productionStaffCannotDispatchOrAdjustStock(){assertTrue(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.CREATE_PRODUCTION));assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.POST_DISPATCH));assertFalse(FactoryPermissions.allows(FactoryRole.PRODUCTION_STAFF,FactoryAction.ADJUST_STOCK))}
 @Test fun dispatchStaffCannotManageStaff(){assertTrue(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.POST_DISPATCH));assertFalse(FactoryPermissions.allows(FactoryRole.DISPATCH_STAFF,FactoryAction.MANAGE_STAFF))}
 @Test fun crossOrganisationAccessDenied(){FactoryPermissions.requireOrganisation("factory-a","factory-a");assertThrows(IllegalArgumentException::class.java){FactoryPermissions.requireOrganisation("factory-a","factory-b")}}
 @Test fun firebaseOperatorClaimMapsToProductionRole(){assertEquals(FactoryRole.PRODUCTION_STAFF,FactoryPermissions.fromClaim("operator"))}
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
}
