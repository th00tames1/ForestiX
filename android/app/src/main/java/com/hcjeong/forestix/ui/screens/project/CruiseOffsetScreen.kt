// Cruise-flow wrapper around OffsetFlowScreen — the Android analogue of the
// iOS CruiseFlowScreen.offsetScreen(plannedId:) step builder. Loads the
// project + design into a CruiseFlowCoordinator, hosts the Offset-from-
// Opening flow, and on completion persists the plot centre (acceptCenter)
// then replaces this step with the tally — matching the iOS
// path.removeLast() + push(.tally) semantics.

package com.hcjeong.forestix.ui.screens.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.nav.OffsetFlowScreen
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun CruiseOffsetScreen(nav: NavController, projectId: String, plannedPlotId: String) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<CruiseFlowCoordinator?>(null) }

    LaunchedEffect(projectId) {
        val id = UUID.fromString(projectId)
        val project = env.projectRepository.read(id) ?: return@LaunchedEffect
        val design = env.cruiseDesignRepository.forProject(id).firstOrNull()
            ?: return@LaunchedEffect
        val coordinator = CruiseFlowCoordinator(project, design)
        coordinator.configure(env)
        coordinator.refresh()
        flow = coordinator
    }

    val coordinator = flow
    if (coordinator == null) {
        ForestixScaffold(nav, title = "Offset-from-opening") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val errorMessage by coordinator.errorMessage.collectAsState()
    val plannedId = remember(plannedPlotId) { UUID.fromString(plannedPlotId) }

    OffsetFlowScreen(
        nav = nav,
        onDone = { result ->
            scope.launch {
                val plot = coordinator.acceptCenter(plannedId, result) ?: return@launch
                nav.navigate(CruiseStepRoutes.tally(projectId, plot.id.toString())) {
                    popUpTo(CruiseStepRoutes.OFFSET) { inclusive = true }
                }
            }
        })

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { coordinator.errorMessage.value = null },
            title = { Text("Couldn't save the plot centre") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { coordinator.errorMessage.value = null }) { Text("OK") }
            })
    }
}
