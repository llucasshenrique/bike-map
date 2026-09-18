package com.ebike.router.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ebike.router.data.local.entity.DestinationCategory
import com.ebike.router.data.local.entity.SavedDestinationEntity
import com.ebike.router.model.PlaceCategory
import com.ebike.router.model.SearchResultItem
import com.ebike.router.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointSearchDialog(
    targetIndex: Int,
    targetLetter: String,
    searchResults: List<SearchResultItem>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onSelectResult: (SearchResultItem) -> Unit,
    onUseGps: () -> Unit,
    savedDestinations: List<SavedDestinationEntity> = emptyList(),
    onSelectSavedDestination: ((SavedDestinationEntity) -> Unit)? = null,
    onSaveSearchResultAsFavorite: ((SearchResultItem) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Buscar Ponto $targetLetter",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Nome da rua, bairro, cidade...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanGlow) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; onSearch("") }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // GPS Button
                OutlinedButton(
                    onClick = onUseGps,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldGreen),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Usar Minha Localização Atual (GPS)", fontWeight = FontWeight.Bold)
                }

                // Quick Favorites Row
                if (query.isBlank() && savedDestinations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Favoritos Rápidos:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate400)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(savedDestinations) { dest ->
                            val icon = when (dest.category) {
                                DestinationCategory.HOME -> Icons.Default.Home
                                DestinationCategory.WORK -> Icons.Default.Work
                                DestinationCategory.TRAIL -> Icons.Default.Terrain
                                DestinationCategory.POI -> Icons.Default.Place
                                else -> Icons.Default.Star
                            }
                            Surface(
                                onClick = { onSelectSavedDestination?.invoke(dest) },
                                shape = RoundedCornerShape(16.dp),
                                color = Slate800,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(icon, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(14.dp))
                                    Text(dest.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CyanPrimary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults) { res ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectResult(res) },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate800)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val icon = when (res.category) {
                                        PlaceCategory.CITY -> Icons.Default.LocationCity
                                        PlaceCategory.POI -> Icons.Default.EvStation
                                        else -> Icons.Default.Place
                                    }
                                    Icon(icon, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(22.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = res.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                        Text(text = res.subText, color = Slate400, fontSize = 11.sp, maxLines = 1)
                                    }
                                    if (onSaveSearchResultAsFavorite != null) {
                                        IconButton(
                                            onClick = { onSaveSearchResultAsFavorite(res) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.BookmarkAdd,
                                                contentDescription = "Salvar nos Favoritos",
                                                tint = AmberWarning,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
