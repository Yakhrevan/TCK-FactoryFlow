package com.tck.factoryflow.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

data class FactorySession(val uid:String,val email:String?,val organisationId:String,val role:String)

class FactoryAuthRepository(context:Context) {
 private val app=FirebaseApp.initializeApp(context.applicationContext)
 private val auth:FirebaseAuth?=app?.let{FirebaseAuth.getInstance(it)}
 private fun requireAuth()=auth?:error("Firebase is not configured. Add the environment-specific google-services.json after creating the Firebase project.")
 suspend fun signIn(email:String,password:String):FactorySession {
  require(email.isNotBlank()&&password.isNotBlank()){"Email and password are required."}
  val result=requireAuth().signInWithEmailAndPassword(email.trim(),password).await()
  val user=result.user?:error("Firebase returned no user.")
  val token=user.getIdToken(true).await()
  val claims=token.claims
  val org=claims["organisationId"] as? String ?: error("This account has no organisation assignment. Ask an organisation administrator.")
  val role=claims["role"] as? String ?: error("This account has no assigned staff role.")
  require(role in setOf("owner","admin","manager","operator","viewer")){"Unsupported staff role."}
  return FactorySession(user.uid,user.email,org,role)
 }
 suspend fun currentSession():FactorySession? {
  val user=requireAuth().currentUser?:return null
  val token=user.getIdToken(false).await()
  val org=token.claims["organisationId"] as? String ?:return null
  val role=token.claims["role"] as? String ?:return null
  return FactorySession(user.uid,user.email,org,role)
 }
 fun signOut(){requireAuth().signOut()}
}
