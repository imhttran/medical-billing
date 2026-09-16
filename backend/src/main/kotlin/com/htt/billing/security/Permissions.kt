package com.htt.billing.security

/**
 * The permission codes seeded by V2__billing_rbac.sql.
 *
 * Authorization always goes through one of these constants, so a typo'd
 * permission is a compile error rather than a check that silently always
 * denies. [ALL] is asserted against the seeded rows by CrossOrganizationAccessTest,
 * which is what keeps this list and the migration from drifting apart.
 */
object Permissions {

    const val SYSTEM_VIEW = "SYSTEM_VIEW"
    const val SYSTEM_CONFIGURE = "SYSTEM_CONFIGURE"
    const val SYSTEM_RESET = "SYSTEM_RESET"

    const val ORGANIZATION_CREATE = "ORGANIZATION_CREATE"
    const val ORGANIZATION_VIEW = "ORGANIZATION_VIEW"
    const val ORGANIZATION_EDIT = "ORGANIZATION_EDIT"
    const val ORGANIZATION_DISABLE = "ORGANIZATION_DISABLE"

    const val USER_VIEW = "USER_VIEW"
    const val USER_CREATE = "USER_CREATE"
    const val USER_EDIT = "USER_EDIT"
    const val USER_DISABLE = "USER_DISABLE"
    const val USER_RESET_PASSWORD = "USER_RESET_PASSWORD"

    const val ROLE_VIEW = "ROLE_VIEW"
    const val ROLE_ASSIGN = "ROLE_ASSIGN"
    const val ROLE_MANAGE = "ROLE_MANAGE"

    const val PATIENT_VIEW = "PATIENT_VIEW"
    const val PATIENT_CREATE = "PATIENT_CREATE"
    const val PATIENT_EDIT = "PATIENT_EDIT"

    const val COVERAGE_VIEW = "COVERAGE_VIEW"
    const val COVERAGE_EDIT = "COVERAGE_EDIT"

    const val PROVIDER_VIEW = "PROVIDER_VIEW"
    const val PROVIDER_MANAGE = "PROVIDER_MANAGE"

    const val CLAIM_VIEW = "CLAIM_VIEW"
    const val CLAIM_CREATE = "CLAIM_CREATE"
    const val CLAIM_EDIT = "CLAIM_EDIT"
    const val CLAIM_SUBMIT = "CLAIM_SUBMIT"
    const val CLAIM_RESUBMIT = "CLAIM_RESUBMIT"
    const val CLAIM_VOID = "CLAIM_VOID"

    const val WORK_QUEUE_VIEW = "WORK_QUEUE_VIEW"
    const val WORK_QUEUE_ASSIGN = "WORK_QUEUE_ASSIGN"
    const val WORK_QUEUE_RESOLVE = "WORK_QUEUE_RESOLVE"

    const val PAYMENT_VIEW = "PAYMENT_VIEW"
    const val PAYMENT_RECORD = "PAYMENT_RECORD"

    const val AUDIT_VIEW = "AUDIT_VIEW"

    const val FHIR_IMPORT = "FHIR_IMPORT"
    const val FHIR_EXPORT = "FHIR_EXPORT"

    val ALL: Set<String> = setOf(
        SYSTEM_VIEW, SYSTEM_CONFIGURE, SYSTEM_RESET,
        ORGANIZATION_CREATE, ORGANIZATION_VIEW, ORGANIZATION_EDIT, ORGANIZATION_DISABLE,
        USER_VIEW, USER_CREATE, USER_EDIT, USER_DISABLE, USER_RESET_PASSWORD,
        ROLE_VIEW, ROLE_ASSIGN, ROLE_MANAGE,
        PATIENT_VIEW, PATIENT_CREATE, PATIENT_EDIT,
        COVERAGE_VIEW, COVERAGE_EDIT,
        PROVIDER_VIEW, PROVIDER_MANAGE,
        CLAIM_VIEW, CLAIM_CREATE, CLAIM_EDIT, CLAIM_SUBMIT, CLAIM_RESUBMIT, CLAIM_VOID,
        WORK_QUEUE_VIEW, WORK_QUEUE_ASSIGN, WORK_QUEUE_RESOLVE,
        PAYMENT_VIEW, PAYMENT_RECORD,
        AUDIT_VIEW,
        FHIR_IMPORT, FHIR_EXPORT,
    )
}

/** The predefined V1 roles seeded by V2__billing_rbac.sql. */
object RoleCodes {

    const val PLATFORM_ADMIN = "PLATFORM_ADMIN"
    const val PRACTICE_ADMIN = "PRACTICE_ADMIN"
    const val BILLING_MANAGER = "BILLING_MANAGER"
    const val BILLER = "BILLER"
    const val PROVIDER = "PROVIDER"
    const val READ_ONLY = "READ_ONLY"

    val ALL: Set<String> = setOf(
        PLATFORM_ADMIN, PRACTICE_ADMIN, BILLING_MANAGER, BILLER, PROVIDER, READ_ONLY,
    )
}

/**
 * A role's scope, matching the `roles.scope_type` check constraint. PLATFORM
 * roles are assigned with no organization; ORGANIZATION roles are assigned with
 * one, and that pairing is what makes the tenant boundary meaningful.
 */
object ScopeTypes {

    const val PLATFORM = "PLATFORM"
    const val ORGANIZATION = "ORGANIZATION"
}
