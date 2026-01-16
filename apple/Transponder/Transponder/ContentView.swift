//
//  ContentView.swift
//  Transponder
//
//  Created by Allison Bentley on 2026-01-06.
//

import SwiftUI

struct ContentView: View {
    @StateObject private var identityStore = IdentityStore.shared
    @State private var pendingFriendLink: String?
    @State private var showOnboarding = false

    var body: some View {
        MainView(
            identityStore: identityStore,
            delayLocationRequest: !identityStore.hasIdentity,
            suppressSheet: showOnboarding,
            pendingFriendLink: $pendingFriendLink
        )
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView(identityStore: identityStore) {
                showOnboarding = false
            }
            .presentationBackground(.clear)
            .interactiveDismissDisabled()
        }
        .onAppear {
            if !identityStore.hasIdentity {
                showOnboarding = true
            }
        }
        .onOpenURL { url in
            handleIncomingURL(url)
        }
    }

    private func handleIncomingURL(_ url: URL) {
        let urlString = url.absoluteString
        guard urlString.hasPrefix("coord://") else { return }

        if identityStore.hasIdentity {
            // Pass to MainView for handling
            pendingFriendLink = urlString
        } else {
            // Save for after onboarding - MainView will pick it up
            pendingFriendLink = urlString
        }
    }
}

#Preview {
    ContentView()
}
