package com.familyguardpro.utils

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * FamilyGuard Pro - Professional Error Handler
 * Provides user-friendly error messages for all error scenarios
 */
object ErrorHandler {

    data class AppError(
        val code: String,
        val title: String,
        val message: String,
        val detail: String
    )

    // Parse error from API response
    fun parseError(throwable: Throwable): AppError {
        return when (throwable) {
            is HttpException -> parseHttpError(throwable)
            is ConnectException, is UnknownHostException -> AppError(
                code = "NET_001",
                title = "No Internet Connection",
                message = "Please check your internet connection",
                detail = "Make sure you're connected to WiFi or mobile data and try again"
            )
            is SocketTimeoutException -> AppError(
                code = "NET_002",
                title = "Connection Timed Out",
                message = "The server is taking too long to respond",
                detail = "Please check your connection and try again"
            )
            else -> AppError(
                code = "SRV_001",
                title = "Something Went Wrong",
                message = "An unexpected error occurred",
                detail = throwable.message ?: "Please try again later"
            )
        }
    }

    private fun parseHttpError(exception: HttpException): AppError {
        val errorBody = exception.response()?.errorBody()?.string()
        
        return try {
            if (errorBody != null) {
                val json = JSONObject(errorBody)
                
                // Check for new error format
                if (json.has("error") && json.get("error") is JSONObject) {
                    val errorObj = json.getJSONObject("error")
                    AppError(
                        code = errorObj.optString("code", "UNKNOWN"),
                        title = getErrorTitle(errorObj.optString("code", "")),
                        message = errorObj.optString("message", "An error occurred"),
                        detail = errorObj.optString("detail", "Please try again")
                    )
                } 
                // Old error format (simple string)
                else if (json.has("error")) {
                    val errorMessage = json.getString("error")
                    mapOldErrorFormat(exception.code(), errorMessage)
                }
                else {
                    getDefaultError(exception.code())
                }
            } else {
                getDefaultError(exception.code())
            }
        } catch (e: Exception) {
            getDefaultError(exception.code())
        }
    }

    private fun mapOldErrorFormat(statusCode: Int, message: String): AppError {
        return when {
            message.contains("email", ignoreCase = true) && message.contains("password", ignoreCase = true) -> 
                AppError("AUTH_008", "Login Failed", "Invalid email or password", "Please check your credentials and try again")
            
            message.contains("already registered", ignoreCase = true) || message.contains("already exists", ignoreCase = true) ->
                AppError("AUTH_005", "Email Already Registered", "This email is already registered", "Please login or use a different email")
            
            message.contains("not found", ignoreCase = true) ->
                AppError("AUTH_006", "Account Not Found", "No account found with this email", "Please check your email or register a new account")
            
            message.contains("password", ignoreCase = true) && message.contains("incorrect", ignoreCase = true) ->
                AppError("AUTH_007", "Incorrect Password", "The password you entered is incorrect", "Please try again or reset your password")
            
            message.contains("invalid", ignoreCase = true) && message.contains("code", ignoreCase = true) ->
                AppError("PAIR_002", "Invalid Code", "Invalid pairing code", "Please check the code and try again")
            
            message.contains("expired", ignoreCase = true) ->
                AppError("PAIR_003", "Code Expired", "Pairing code has expired", "Please generate a new code from the parent device")
            
            message.contains("token", ignoreCase = true) ->
                AppError("AUTH_011", "Session Expired", "Your session has expired", "Please login again")
            
            else -> getDefaultError(statusCode)
        }
    }

    private fun getErrorTitle(code: String): String {
        return when {
            code.startsWith("AUTH") -> "Authentication Error"
            code.startsWith("PAIR") -> "Pairing Error"
            code.startsWith("DEV") -> "Device Error"
            code.startsWith("SYNC") -> "Sync Error"
            code.startsWith("NET") -> "Network Error"
            else -> "Error"
        }
    }

    private fun getDefaultError(statusCode: Int): AppError {
        return when (statusCode) {
            400 -> AppError("ERR_400", "Bad Request", "Invalid request", "Please check your input and try again")
            401 -> AppError("ERR_401", "Unauthorized", "Please login to continue", "Your session may have expired")
            403 -> AppError("ERR_403", "Access Denied", "You don't have permission", "Please contact support if this persists")
            404 -> AppError("ERR_404", "Not Found", "Resource not found", "The requested item could not be found")
            409 -> AppError("ERR_409", "Conflict", "Resource already exists", "Please try with different data")
            429 -> AppError("ERR_429", "Too Many Requests", "Please slow down", "Wait a moment and try again")
            500, 502, 503 -> AppError("ERR_500", "Server Error", "Server is having issues", "Please try again later")
            else -> AppError("ERR_$statusCode", "Error", "Something went wrong", "Please try again")
        }
    }

    // Show error as Toast
    fun showToast(context: Context, throwable: Throwable) {
        val error = parseError(throwable)
        Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
    }

    // Show error as simple Toast with message only
    fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // Show error as Alert Dialog (more detailed)
    fun showDialog(context: Context, throwable: Throwable, onRetry: (() -> Unit)? = null) {
        val error = parseError(throwable)
        showDialog(context, error, onRetry)
    }

    fun showDialog(context: Context, error: AppError, onRetry: (() -> Unit)? = null) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(error.title)
            .setMessage("${error.message}\n\n${error.detail}")
            .setPositiveButton("OK", null)
        
        if (onRetry != null) {
            builder.setNegativeButton("Retry") { _, _ -> onRetry() }
        }
        
        builder.show()
    }

    // Show error with custom action
    fun showDialogWithAction(
        context: Context,
        throwable: Throwable,
        actionText: String,
        onAction: () -> Unit
    ) {
        val error = parseError(throwable)
        MaterialAlertDialogBuilder(context)
            .setTitle(error.title)
            .setMessage("${error.message}\n\n${error.detail}")
            .setPositiveButton(actionText) { _, _ -> onAction() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Check if error requires re-login
    fun requiresReLogin(throwable: Throwable): Boolean {
        val error = parseError(throwable)
        return error.code in listOf(
            "AUTH_009", "AUTH_010", "AUTH_011", "AUTH_TOKEN_MISSING",
            "AUTH_TOKEN_EXPIRED", "AUTH_TOKEN_INVALID", "ERR_401"
        )
    }

    // Get user-friendly message for display
    fun getDisplayMessage(throwable: Throwable): String {
        return parseError(throwable).message
    }
}
