// Route constants for the tree flow cluster (add-tree stepper + single-tree
// inspector). Kept out of ForestixRoot.kt so the screens in this package can
// navigate among themselves before the integrator wires the composable
// destinations into the NavHost.
//
// Suggested NavHost wiring:
//   composable(TreeFlowRoutes.ADD_TREE) { back ->
//       AddTreeFlowScreen(nav, back.arguments!!.getString("plotId")!!)
//   }
//   composable(TreeFlowRoutes.TREE_DETAIL) { back ->
//       TreeDetailScreen(nav, back.arguments!!.getString("treeId")!!)
//   }
//
// TreeIdentitySheet is not a destination — host it in a ModalBottomSheet
// from the Quick Measure home / measurement hub before opening a scan.

package com.hcjeong.forestix.ui.screens.tree

object TreeFlowRoutes {
    const val ADD_TREE = "addTree/{plotId}"
    const val TREE_DETAIL = "treeDetail/{treeId}"

    fun addTree(plotId: String) = "addTree/$plotId"
    fun treeDetail(treeId: String) = "treeDetail/$treeId"
}
