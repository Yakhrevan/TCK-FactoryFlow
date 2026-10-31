package com.tck.factoryflow.cloud

sealed class CloudResult<out T> {
    data class Success<T>(val data: T) : CloudResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : CloudResult<Nothing>()
}
