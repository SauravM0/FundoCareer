package com.fundocareer.app.core.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import com.fundocareer.app.core.jobalerts.OverallReliability
import com.fundocareer.app.core.jobalerts.ui.theme.FcAmber
import com.fundocareer.app.core.jobalerts.ui.theme.FcAmberLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreen
import com.fundocareer.app.core.jobalerts.ui.theme.FcGreenLight
import com.fundocareer.app.core.jobalerts.ui.theme.FcRed
import com.fundocareer.app.core.jobalerts.ui.theme.FcRedLight
import kotlinx.coroutines.launch

private val TABS = listOf("Job Search", "History")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsPageScreen(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()
    val viewModel: JobsViewModel = viewModel()
    val reliabilityStatus by viewModel.reliabilityStatus.collectAsState()

    LaunchedEffect(pagerState.currentPage) {
        Log.i("JobsPageScreen", "scheduler tab changed: ${TABS[pagerState.currentPage]}")
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reload()
                viewModel.refreshReliability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Jobs", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    reliabilityStatus?.let { status ->
                        val (label, bgColor, fgColor) = when (status.overall) {
                            OverallReliability.OPTIMIZED -> Triple("Optimized", FcGreenLight, FcGreen)
                            OverallReliability.LIMITED -> Triple("Limited", FcAmberLight, FcAmber)
                            OverallReliability.NEEDS_SETUP -> Triple("Needs setup", FcRedLight, FcRed)
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(bgColor)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val icon = when (status.overall) {
                                    OverallReliability.OPTIMIZED -> Icons.Default.CheckCircle
                                    OverallReliability.LIMITED -> Icons.Default.Info
                                    OverallReliability.NEEDS_SETUP -> Icons.Default.Warning
                                }
                                Icon(icon, null, Modifier.size(14.dp), tint = fgColor)
                                Spacer(Modifier.width(4.dp))
                                Text(label, color = fgColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Text(
                "Find fresh LinkedIn jobs faster and apply early.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        height = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                title,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> DiscoverySection(viewModel = viewModel)
                    1 -> HistoryTab(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(viewModel: JobsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
        }
    } else {
        HistorySection(timeline = uiState.timeline)
    }
}
