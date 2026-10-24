package com.tck.factoryflow.auth

enum class FactoryRole { OWNER, ADMIN, MANAGER, PRODUCTION_STAFF, PACKING_STAFF, STOREKEEPER, DISPATCH_STAFF, VIEWER }
enum class FactoryAction { VIEW, CREATE_PRODUCTION, CREATE_PACKING, ADJUST_STOCK, POST_DISPATCH, REVERSE_DISPATCH, MANAGE_PRODUCTS, MANAGE_STAFF, EXPORT_REPORTS, CORRECT_RECORDS }

object FactoryPermissions {
 fun allows(role:FactoryRole,action:FactoryAction):Boolean = when(role) {
  FactoryRole.OWNER -> true
  FactoryRole.ADMIN -> action != FactoryAction.MANAGE_STAFF || true
  FactoryRole.MANAGER -> action !in setOf(FactoryAction.MANAGE_STAFF)
  FactoryRole.PRODUCTION_STAFF -> action in setOf(FactoryAction.VIEW,FactoryAction.CREATE_PRODUCTION)
  FactoryRole.PACKING_STAFF -> action in setOf(FactoryAction.VIEW,FactoryAction.CREATE_PACKING)
  FactoryRole.STOREKEEPER -> action in setOf(FactoryAction.VIEW,FactoryAction.ADJUST_STOCK,FactoryAction.MANAGE_PRODUCTS,FactoryAction.EXPORT_REPORTS)
  FactoryRole.DISPATCH_STAFF -> action in setOf(FactoryAction.VIEW,FactoryAction.POST_DISPATCH)
  FactoryRole.VIEWER -> action in setOf(FactoryAction.VIEW,FactoryAction.EXPORT_REPORTS)
 }
 fun requireOrganisation(sessionOrganisationId:String,recordOrganisationId:String) {
  require(sessionOrganisationId.isNotBlank() && recordOrganisationId.isNotBlank() && sessionOrganisationId==recordOrganisationId) { "Access denied: organisation mismatch." }
 }
 fun requireAllowed(role:FactoryRole,action:FactoryAction) {
  require(allows(role,action)) { "Access denied: role $role cannot perform $action." }
 }
}
