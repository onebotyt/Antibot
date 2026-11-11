package com.example.gemini.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.skills.*
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SkillPurple = Color(0xFF8B5CF6)
private val SkillGreen = Color(0xFF2EA043)
private val SkillAmber = Color(0xFFD29922)
private val SkillRed = Color(0xFFDA3633)
private val SkillBlue = Color(0xFF388BFD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgenticSkillsDialog(
    activeProject: ProjectItem?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Catalog, 1: Bundles, 2: Installed
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var selectedRisk by remember { mutableStateOf("all") }

    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SkillMeta>>(emptyList()) }
    var bundles by remember { mutableStateOf<List<SkillBundle>>(emptyList()) }
    var installedSkills by remember { mutableStateOf<List<String>>(emptyList()) }

    var selectedSkillDetail by remember { mutableStateOf<SkillDetail?>(null) }
    var showTargetPickerForSkill by remember { mutableStateOf<SkillMeta?>(null) }
    var showTargetPickerForBundle by remember { mutableStateOf<SkillBundle?>(null) }

    // Load initial data
    LaunchedEffect(Unit) {
        isSearching = true
        searchResults = AgenticSkillsRepository.searchSkills("", limit = 40, context = context)
        bundles = AgenticSkillsRepository.listBundles()
        val targetDir = if (activeProject != null && activeProject.path.isNotBlank()) {
            File(activeProject.path, ".agents/skills")
        } else {
            File("/data/user/0/com.termux/files/home/.gemini/config/skills")
        }
        installedSkills = AgenticSkillsRepository.listInstalled(targetDir)
        isSearching = false
    }

    // Trigger search when query or filters change
    LaunchedEffect(searchQuery, selectedCategory, selectedRisk) {
        delay(200)
        isSearching = true
        val cat = if (selectedCategory == "all") null else selectedCategory
        val rk = if (selectedRisk == "all") null else selectedRisk
        searchResults = AgenticSkillsRepository.searchSkills(searchQuery, cat, rk, limit = 50, context = context)
        isSearching = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SkillPurple.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Psychology,
                                contentDescription = null,
                                tint = SkillPurple,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Agentic Awesome Skills",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SkillPurple.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = "2,955+",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SkillPurple,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Standardized reusable playbooks for any AI & CLI",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Tabs: Catalog, Bundles, Installed
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = { Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(17.dp))
                                Text("Catalog (${searchResults.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Layers, contentDescription = null, modifier = Modifier.size(17.dp))
                                Text("Bundles (${bundles.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            val targetDir = if (activeProject != null && activeProject.path.isNotBlank()) {
                                File(activeProject.path, ".agents/skills")
                            } else {
                                File("/data/user/0/com.termux/files/home/.gemini/config/skills")
                            }
                            scope.launch {
                                installedSkills = AgenticSkillsRepository.listInstalled(targetDir)
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
                                Text("Installed (${installedSkills.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }

                // Tab Contents
                when (selectedTab) {
                    0 -> CatalogTabContent(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        selectedCategory = selectedCategory,
                        onCategorySelect = { selectedCategory = it },
                        selectedRisk = selectedRisk,
                        onRiskSelect = { selectedRisk = it },
                        isSearching = isSearching,
                        results = searchResults,
                        installedSkills = installedSkills,
                        onInspect = { meta ->
                            scope.launch {
                                selectedSkillDetail = AgenticSkillsRepository.getSkillDetail(meta.id, context)
                            }
                        },
                        onInstall = { meta -> showTargetPickerForSkill = meta }
                    )

                    1 -> BundlesTabContent(
                        bundles = bundles,
                        onInstallBundle = { bundle -> showTargetPickerForBundle = bundle }
                    )

                    2 -> InstalledTabContent(
                        installed = installedSkills,
                        activeProject = activeProject,
                        onInspect = { skillId ->
                            scope.launch {
                                selectedSkillDetail = AgenticSkillsRepository.getSkillDetail(skillId, context)
                            }
                        }
                    )
                }
            }
        }
    }

    // Detail Modal Dialog
    if (selectedSkillDetail != null) {
        SkillDetailModal(
            detail = selectedSkillDetail!!,
            activeProject = activeProject,
            onDismiss = { selectedSkillDetail = null },
            onInstall = {
                val meta = selectedSkillDetail!!.meta
                selectedSkillDetail = null
                showTargetPickerForSkill = meta
            }
        )
    }

    // Target Picker for Single Skill
    if (showTargetPickerForSkill != null) {
        TargetPickerModal(
            title = "Install '${showTargetPickerForSkill!!.name}'",
            activeProject = activeProject,
            onDismiss = { showTargetPickerForSkill = null },
            onTargetSelected = { target ->
                val meta = showTargetPickerForSkill!!
                showTargetPickerForSkill = null
                scope.launch {
                    val res = AgenticSkillsRepository.installSkill(meta.id, target, activeProject?.path)
                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                    val targetDir = if (activeProject != null && activeProject.path.isNotBlank()) {
                        File(activeProject.path, ".agents/skills")
                    } else {
                        File("/data/user/0/com.termux/files/home/.gemini/config/skills")
                    }
                    installedSkills = AgenticSkillsRepository.listInstalled(targetDir)
                }
            }
        )
    }

    // Target Picker for Bundle
    if (showTargetPickerForBundle != null) {
        TargetPickerModal(
            title = "Install Bundle '${showTargetPickerForBundle!!.displayName}' (${showTargetPickerForBundle!!.skillCount} skills)",
            activeProject = activeProject,
            onDismiss = { showTargetPickerForBundle = null },
            onTargetSelected = { target ->
                val bundle = showTargetPickerForBundle!!
                showTargetPickerForBundle = null
                scope.launch {
                    val res = AgenticSkillsRepository.installBundle(bundle.name, target, activeProject?.path)
                    Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                    val targetDir = if (activeProject != null && activeProject.path.isNotBlank()) {
                        File(activeProject.path, ".agents/skills")
                    } else {
                        File("/data/user/0/com.termux/files/home/.gemini/config/skills")
                    }
                    installedSkills = AgenticSkillsRepository.listInstalled(targetDir)
                }
            }
        )
    }
}

@Composable
private fun CatalogTabContent(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit,
    selectedRisk: String,
    onRiskSelect: (String) -> Unit,
    isSearching: Boolean,
    results: List<SkillMeta>,
    installedSkills: List<String>,
    onInspect: (SkillMeta) -> Unit,
    onInstall: (SkillMeta) -> Unit
) {
    val categories = listOf("all", "workflow", "development", "testing", "security", "architecture", "mobile", "devops", "systems")
    val risks = listOf("all", "safe", "low", "medium", "critical")

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search 2,955+ skills (e.g. debugging, android, flutter, rust, auth)...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SkillPurple) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { cat ->
                val selected = selectedCategory == cat
                FilterChip(
                    selected = selected,
                    onClick = { onCategorySelect(cat) },
                    label = { Text(cat.replaceFirstChar { it.uppercase() }, fontSize = 11.5.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SkillPurple.copy(alpha = 0.2f),
                        selectedLabelColor = SkillPurple
                    )
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Risk Filter Chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Risk:", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            risks.forEach { r ->
                val selected = selectedRisk == r
                val chipColor = when (r) {
                    "safe" -> SkillGreen
                    "medium" -> SkillAmber
                    "critical" -> SkillRed
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) chipColor.copy(alpha = 0.2f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (selected) chipColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable { onRiskSelect(r) }
                ) {
                    Text(
                        text = r.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) chipColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Results
        if (isSearching) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SkillPurple, modifier = Modifier.size(32.dp))
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
                    Text("No matching skills found", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id }) { meta ->
                    val isInstalled = installedSkills.contains(meta.id)
                    SkillCard(
                        meta = meta,
                        isInstalled = isInstalled,
                        onInspect = { onInspect(meta) },
                        onInstall = { onInstall(meta) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    meta: SkillMeta,
    isInstalled: Boolean,
    onInspect: () -> Unit,
    onInstall: () -> Unit
) {
    val riskColor = when (meta.risk.lowercase()) {
        "safe" -> SkillGreen
        "low" -> SkillBlue
        "medium" -> SkillAmber
        "critical" -> SkillRed
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onInspect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = meta.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = meta.category,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = riskColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.5.dp, riskColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = meta.risk.uppercase(),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = riskColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(5.dp))

            Text(
                text = meta.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (meta.tags.isNotEmpty()) {
                    Text(
                        text = meta.tags.take(3).joinToString("  ") { "#$it" },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onInspect,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Inspect", fontSize = 11.5.sp)
                    }

                    Button(
                        onClick = onInstall,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInstalled) SkillGreen else SkillPurple
                        )
                    ) {
                        Icon(
                            imageVector = if (isInstalled) Icons.Default.Check else Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isInstalled) "Installed" else "Install", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BundlesTabContent(
    bundles: List<SkillBundle>,
    onInstallBundle: (SkillBundle) -> Unit
) {
    if (bundles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No bundles available", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(bundles, key = { it.name }) { bundle ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SkillBlue.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Layers, contentDescription = null, tint = SkillBlue, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text(
                                    text = bundle.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${bundle.skillCount} curated skills",
                                    fontSize = 11.5.sp,
                                    color = SkillPurple,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Button(
                            onClick = { onInstallBundle(bundle) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SkillPurple)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Install Bundle", fontSize = 11.5.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = bundle.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Includes: " + bundle.skills.take(5).joinToString(", ") + if (bundle.skills.size > 5) "..." else "",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledTabContent(
    installed: List<String>,
    activeProject: ProjectItem?,
    onInspect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        val locationStr = if (activeProject != null && activeProject.path.isNotBlank()) {
            "Active Project (${activeProject.name}/.agents/skills)"
        } else {
            "Global Antigravity (~/.gemini/config/skills)"
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = SkillPurple.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = SkillPurple, modifier = Modifier.size(20.dp))
                Column {
                    Text("Target Directory", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(locationStr, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = SkillPurple)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (installed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(36.dp))
                    Text("No skills installed in this location yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp)
                    Text("Switch to the Catalog tab to install skills!", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(installed) { skillId ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onInspect(skillId) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SkillGreen, modifier = Modifier.size(16.dp))
                                Text(skillId, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                            IconButton(onClick = { onInspect(skillId) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillDetailModal(
    detail: SkillDetail,
    activeProject: ProjectItem?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = detail.meta.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Category: ${detail.meta.category} · Risk: ${detail.meta.risk}",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onInstall,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SkillPurple)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Install", fontSize = 12.sp)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    item {
                        SelectionContainer {
                            Text(
                                text = detail.content,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (detail.files.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(14.dp))
                            Text("Auxiliary Files (${detail.files.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            detail.files.forEach { f ->
                                Text("• $f", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetPickerModal(
    title: String,
    activeProject: ProjectItem?,
    onDismiss: () -> Unit,
    onTargetSelected: (SkillInstallTarget) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Choose where to install this skill:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SkillInstallTarget.values().forEach { target ->
                    val isProject = target == SkillInstallTarget.PROJECT
                    val enabled = !isProject || (activeProject != null && activeProject.path.isNotBlank())
                    val subtitle = when (target) {
                        SkillInstallTarget.PROJECT -> if (enabled) "${activeProject?.name}/.agents/skills" else "No active project open"
                        SkillInstallTarget.GEMINI -> "~/.gemini/config/skills"
                        SkillInstallTarget.CODEX -> "~/.codex/skills"
                        SkillInstallTarget.CLAUDE -> "~/.claude/skills"
                        SkillInstallTarget.OPENCODE -> "~/.opencode/skills"
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onTargetSelected(target) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = target.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    text = subtitle,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (enabled) SkillPurple else MaterialTheme.colorScheme.outline
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = if (enabled) SkillPurple else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
