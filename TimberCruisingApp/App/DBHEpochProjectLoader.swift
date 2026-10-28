import Models

/// Application composition is separate from the host-testable legacy sweep.
extension DBHEpochRecompute {
    @MainActor
    public static func loadProjects(environment: AppEnvironment) -> [ProjectTrees] {
        guard let projects = try? environment.projectRepository.list() else { return [] }
        return projects.compactMap { project in
            guard let plots = try? environment.plotRepository.listByProject(project.id)
            else { return nil }
            var trees: [Tree] = []
            for plot in plots {
                guard let t = try? environment.treeRepository.listByPlot(plot.id) else { continue }
                trees += t
            }
            guard !trees.isEmpty else { return nil }
            return ProjectTrees(projectID: project.id,
                                projectName: project.name,
                                trees: trees)
        }
    }
}
