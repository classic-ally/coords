import SwiftUI

struct LicensesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showingDependencies = false

    private var totalPackages: Int {
        getLicenses().reduce(0) { $0 + $1.packages.count }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 16) {
                        // AGPL Logo
                        Image("agpl-logo")
                            .resizable()
                            .scaledToFit()
                            .frame(height: 80)

                        VStack(spacing: 4) {
                            Text("Coords for iOS")
                                .font(.headline)
                            Text("v\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0") · Core \(getVersion())")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        VStack(spacing: 4) {
                            Text("© 2026 Allison Bentley")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Text("Made with ❤️ for Helen")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)

                    // Dependencies row
                    NavigationLink {
                        DependenciesListView()
                    } label: {
                        HStack {
                            Text("Dependencies")
                            Spacer()
                            Text("\(totalPackages)")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

struct DependenciesListView: View {
    @State private var expandedLicenses: Set<String> = []

    var body: some View {
        List {
            let licenses = getLicenses()
            ForEach(licenses, id: \.id) { group in
                DisclosureGroup(
                    isExpanded: Binding(
                        get: { expandedLicenses.contains(group.id) },
                        set: { isExpanded in
                            if isExpanded {
                                expandedLicenses.insert(group.id)
                            } else {
                                expandedLicenses.remove(group.id)
                            }
                        }
                    )
                ) {
                    VStack(alignment: .leading, spacing: 12) {
                        // Package list
                        Text(group.packages.joined(separator: ", "))
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Divider()

                        // License text
                        Text(group.text)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.name)
                            .font(.body)
                        Text("\(group.packages.count) packages")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Dependencies")
        .navigationBarTitleDisplayMode(.inline)
    }
}
