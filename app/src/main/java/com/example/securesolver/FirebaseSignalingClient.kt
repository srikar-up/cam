package com.example.securesolver

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class FirebaseSignalingClient(
    context: android.content.Context,
    databaseUrl: String,
    apiKey: String,
    applicationId: String
) {
    private val db: FirebaseDatabase

    init {
        val appName = "SecureSolverFirebaseApp"
        val existingApp = FirebaseApp.getApps(context).find { it.name == appName }
        val app = existingApp ?: run {
            val options = FirebaseOptions.Builder()
                .setDatabaseUrl(databaseUrl)
                .setApiKey(apiKey)
                .setApplicationId(applicationId)
                .build()
            FirebaseApp.initializeApp(context, options, appName)
        }
        db = FirebaseDatabase.getInstance(app)
    }

    private fun getRoomRef(roomId: String): DatabaseReference {
        return db.getReference("rooms").child(roomId)
    }

    fun createRoom(roomId: String, offerSdp: String) {
        val ref = getRoomRef(roomId)
        ref.child("offerSdp").setValue(offerSdp)
    }

    fun sendAnswer(roomId: String, answerSdp: String) {
        val ref = getRoomRef(roomId)
        ref.child("answerSdp").setValue(answerSdp)
    }

    fun sendIceCandidate(roomId: String, isCamera: Boolean, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        val ref = getRoomRef(roomId).child("iceCandidates").push()
        ref.setValue(mapOf(
            "isCamera" to isCamera,
            "sdp" to sdp,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex
        ))
    }

    fun observeOffer(roomId: String): Flow<String> = callbackFlow {
        val ref = getRoomRef(roomId).child("offerSdp")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.getValue(String::class.java)
                if (sdp != null) {
                    trySend(sdp)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeAnswer(roomId: String): Flow<String> = callbackFlow {
        val ref = getRoomRef(roomId).child("answerSdp")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.getValue(String::class.java)
                if (sdp != null) {
                    trySend(sdp)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeIceCandidates(roomId: String): Flow<Map<String, Any>> = callbackFlow {
        val ref = getRoomRef(roomId).child("iceCandidates")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val map = snapshot.value as? Map<String, Any>
                if (map != null) {
                    trySend(map)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeChildEventListener(listener) }
    }

    fun clearRoom(roomId: String) {
        getRoomRef(roomId).removeValue()
    }
}
