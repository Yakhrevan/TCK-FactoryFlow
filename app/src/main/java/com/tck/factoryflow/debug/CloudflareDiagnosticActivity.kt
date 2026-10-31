package com.tck.factoryflow.debug

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudflareDiagnosticActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var authButton: Button
    private lateinit var firestoreButton: Button
    private lateinit var inventoryButton: Button
    private lateinit var productButton: Button
    private lateinit var productionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Cloudflare Diagnostics"
            textSize = 22f
            setTextColor(Color.BLACK)
        }

        authButton = Button(this).apply {
            text = "Test Firebase Authentication"
        }

        firestoreButton = Button(this).apply {
            text = "Test Firestore Connection"
        }

        inventoryButton = Button(this).apply {
            text = "Test Inventory Snapshot"
        }

        productButton = Button(this).apply {
            text = "Test Product Upsert"
        }

        productionButton = Button(this).apply {
            text = "Test Production Posting"
        }

        output = TextView(this).apply {
            text = "Signed-in Firebase user required.\n"
            textSize = 16f
            setTextColor(Color.BLACK)
        }

        layout.addView(title)
        layout.addView(authButton)
        layout.addView(firestoreButton)
        layout.addView(inventoryButton)
        layout.addView(productButton)
        layout.addView(productionButton)
        layout.addView(output)

        setContentView(ScrollView(this).apply {
            addView(layout)
        })

        authButton.setOnClickListener {
            runDiagnostic("Authentication") {
                CloudflareDiagnostic.testAuthentication()
            }
        }

        firestoreButton.setOnClickListener {
            runDiagnostic("Firestore") {
                CloudflareDiagnostic.testFirestore()
            }
        }

        inventoryButton.setOnClickListener {
            runDiagnostic("Inventory Snapshot") {
                CloudflareDiagnostic.testInventorySnapshot()
            }
        }

        productButton.setOnClickListener {
            runDiagnostic("Product Upsert") {
                CloudflareDiagnostic.testProductUpsert()
            }
        }

        productionButton.setOnClickListener {
            runDiagnostic("Production Posting") {
                CloudflareDiagnostic.testProductionPost()
            }
        }
    }

    private fun runDiagnostic(
        name: String,
        test: suspend () -> String
    ) {
        authButton.isEnabled = false
        firestoreButton.isEnabled = false
        inventoryButton.isEnabled = false
        productButton.isEnabled = false
        productionButton.isEnabled = false

        output.text = "Testing $name..."

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { test() }
                    .getOrElse {
                        "FAILED: ${it.javaClass.simpleName}: ${it.message}"
                    }
            }

            output.text = "$name result:\n\n$result"

            authButton.isEnabled = true
            firestoreButton.isEnabled = true
            inventoryButton.isEnabled = true
            productButton.isEnabled = true
            productionButton.isEnabled = true
        }
    }
}
