//
//  TransponderApp.swift
//  Transponder
//
//  Created by Allison Bentley on 2026-01-06.
//

import SwiftUI
import BackgroundTasks

@main
struct TransponderApp: App {
    @StateObject private var backgroundSyncManager = BackgroundSyncManager.shared

    init() {
        // Initialize the Rust library early
        uniffiEnsureTransponderCoreInitialized()

        // Initialize storage with app's documents directory
        initializeStorage()

        // Register background tasks
        BackgroundSyncManager.shared.registerBackgroundTasks()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                    // Schedule background refresh when app becomes active
                    if IdentityStore.shared.autoShareEnabled {
                        BackgroundSyncManager.shared.scheduleBackgroundRefresh()
                    }
                }
        }
        .handlesExternalEvents(matching: ["transponder"])
    }

    private func initializeStorage() {
        let documentsPath = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first!.path

        do {
            try initStorage(path: documentsPath)
            // Migrate friends from old server domain to new one
            let migrated = try migrateServerUrls(
                fromDomain: "transponder.bentley.sh",
                toDomain: "coord.is"
            )
            #if DEBUG
            if migrated > 0 {
                print("Migrated \(migrated) friend(s) to coord.is")
            }
            #endif
        } catch {
            #if DEBUG
            print("Failed to initialize storage: \(error)")
            #endif
        }

        // Migrate user's own server URL
        IdentityStore.shared.migrateServerUrl(
            from: "transponder.bentley.sh",
            to: "coord.is"
        )
    }
}
