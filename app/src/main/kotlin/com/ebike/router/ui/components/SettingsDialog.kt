package com.ebike.router.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ebike.router.data.UserBikePreferences
import com.ebike.router.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    preferences: UserBikePreferences,
    onUpdateEBikeMode: (Boolean) -> Unit,
    onSaveSpecs: (
        batteryCapacityWh: Double,
        maxAssistSpeedKmh: Double,
        bikeWeightKg: Double,
        riderWeightKg: Double,
        regenerativeBraking: Boolean
    ) -> Unit,
    onUpdateCurrentBatteryWh: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var isEBike by remember { mutableStateOf(preferences.isEBikeMode) }
    var batteryCapacityText by remember { mutableStateOf(preferences.batteryCapacityWh.toInt().toString()) }
    var maxSpeedText by remember { mutableStateOf(preferences.maxAssistSpeedKmh.toInt().toString()) }
    var bikeWeightText by remember { mutableStateOf(preferences.bikeWeightKg.toInt().toString()) }
    var riderWeightText by remember { mutableStateOf(preferences.riderWeightKg.toInt().toString()) }
    var regenBraking by remember { mutableStateOf(preferences.regenerativeBraking) }

    val initialPercent = if (preferences.batteryCapacityWh > 0) {
        ((preferences.currentBatteryWh / preferences.batteryCapacityWh) * 100).roundToInt().coerceIn(0, 100)
    } else 100
    var batteryPercentSlider by remember { mutableFloatStateOf(initialPercent.toFloat()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(CyanPrimary.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = CyanGlow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Configurações da Bicicleta",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Opt-in Toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isEBike) CyanPrimary.copy(alpha = 0.12f) else Slate800
                    ),
                    border = if (isEBike) androidx.compose.foundation.BorderStroke(1.5.dp, CyanGlow) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                if (isEBike) Icons.Default.ElectricBike else Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = if (isEBike) CyanGlow else Slate400,
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Tenho uma Bicicleta Elétrica",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isEBike) "Modo E-Bike ativado com estimativas de bateria."
                                    else "Desative para usar como ciclocomputador tradicional.",
                                    fontSize = 11.sp,
                                    color = Slate400,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                        Switch(
                            checked = isEBike,
                            onCheckedChange = {
                                isEBike = it
                                onUpdateEBikeMode(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = CyanGlow,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate700
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isEBike) {
                    // Normal Bike Informational Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.8f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.DirectionsBike, contentDescription = null, tint = EmeraldGreen)
                                Text(
                                    text = "Modo Bicicleta Convencional Ativo",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldGreen
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "O aplicativo opera como ciclocomputador tradicional. Suas rotas e tempos são calculados considerando pedalada 100% humana sem assistência de motor elétrico. Indicadores de bateria e modos de potência ficam ocultos para uma navegação limpa.",
                                fontSize = 12.sp,
                                color = Slate400,
                                lineHeight = 18.sp
                            )
                        }
                    }
                } else {
                    // E-Bike Specification Form
                    // Simulation Disclaimer Banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.12f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("⚠️", fontSize = 18.sp)
                            Column {
                                Text(
                                    text = "ESTIMATIVAS POR SIMULAÇÃO MATEMÁTICA",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = AmberWarning
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Este app não possui conexão Bluetooth (BLE) com a sua bicicleta. Todos os dados de bateria, autonomia e potência são estimados por física simulada com base nos parâmetros abaixo.",
                                    fontSize = 11.sp,
                                    color = Slate200,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Battery Capacity (Wh)
                    Text(
                        text = "Capacidade da Bateria (Wh):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = batteryCapacityText,
                        onValueChange = { batteryCapacityText = it.filter { char -> char.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanGlow,
                            unfocusedBorderColor = Slate700,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Slate800,
                            unfocusedContainerColor = Slate800
                        ),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = { Text("Wh", color = Slate400, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp)) }
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    // Battery Presets Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(250, 400, 500, 625, 750).forEach { wh ->
                            val isChipSelected = batteryCapacityText == wh.toString()
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { batteryCapacityText = wh.toString() },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChipSelected) CyanPrimary else Slate800,
                                border = if (!isChipSelected) androidx.compose.foundation.BorderStroke(1.dp, Slate700) else null
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${wh}Wh",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isChipSelected) Slate950 else Color.White
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Max Assist Speed
                    Text(
                        text = "Velocidade Máxima de Assistência (km/h):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = maxSpeedText,
                        onValueChange = { maxSpeedText = it.filter { char -> char.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanGlow,
                            unfocusedBorderColor = Slate700,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Slate800,
                            unfocusedContainerColor = Slate800
                        ),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = { Text("km/h", color = Slate400, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp)) }
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    // Speed Presets Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            25 to "25 km/h (UE)",
                            32 to "32 km/h (Brasil)",
                            45 to "45 km/h (Speed)"
                        ).forEach { (spd, lbl) ->
                            val isChipSelected = maxSpeedText == spd.toString()
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { maxSpeedText = spd.toString() },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChipSelected) CyanPrimary else Slate800,
                                border = if (!isChipSelected) androidx.compose.foundation.BorderStroke(1.dp, Slate700) else null
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = lbl,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isChipSelected) Slate950 else Color.White
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Bike & Rider Weights
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Peso Bike (kg):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = bikeWeightText,
                                onValueChange = { bikeWeightText = it.filter { char -> char.isDigit() } },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanGlow,
                                    unfocusedBorderColor = Slate700,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Slate800,
                                    unfocusedContainerColor = Slate800
                                ),
                                shape = RoundedCornerShape(12.dp),
                                trailingIcon = { Text("kg", color = Slate400, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp)) }
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ciclista+Carga (kg):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate400)
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = riderWeightText,
                                onValueChange = { riderWeightText = it.filter { char -> char.isDigit() } },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanGlow,
                                    unfocusedBorderColor = Slate700,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Slate800,
                                    unfocusedContainerColor = Slate800
                                ),
                                shape = RoundedCornerShape(12.dp),
                                trailingIcon = { Text("kg", color = Slate400, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp)) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Current Battery Level Slider
                    val parsedCap = batteryCapacityText.toDoubleOrNull() ?: 500.0
                    val liveWh = (parsedCap * (batteryPercentSlider / 100.0)).roundToInt()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nível Atual da Bateria:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate400
                        )
                        Text(
                            text = "${batteryPercentSlider.roundToInt()}% (~$liveWh Wh)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldGreen
                        )
                    }
                    Slider(
                        value = batteryPercentSlider,
                        onValueChange = {
                            batteryPercentSlider = it
                            val newCurrentWh = parsedCap * (it / 100.0)
                            onUpdateCurrentBatteryWh(newCurrentWh)
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = EmeraldGreen,
                            activeTrackColor = EmeraldGreen,
                            inactiveTrackColor = Slate800
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Regenerative braking toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { regenBraking = !regenBraking },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Freio Regenerativo", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Simular pequena recarga em descidas íngremes", fontSize = 11.sp, color = Slate400)
                        }
                        Switch(
                            checked = regenBraking,
                            onCheckedChange = { regenBraking = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = EmeraldGreen,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate700
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save / Close Button
                Button(
                    onClick = {
                        if (isEBike) {
                            val cap = batteryCapacityText.toDoubleOrNull() ?: 500.0
                            val spd = maxSpeedText.toDoubleOrNull() ?: 32.0
                            val bWeight = bikeWeightText.toDoubleOrNull() ?: 24.0
                            val rWeight = riderWeightText.toDoubleOrNull() ?: 75.0
                            onSaveSpecs(cap, spd, bWeight, rWeight, regenBraking)
                            val curWh = cap * (batteryPercentSlider / 100.0)
                            onUpdateCurrentBatteryWh(curWh)
                        }
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = Slate950
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isEBike) "SALVAR ESPECIFICAÇÕES" else "CONCLUIR",
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
