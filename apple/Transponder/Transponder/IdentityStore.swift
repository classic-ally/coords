import Foundation
import Security
import Combine
import CoreLocation

/// Secure storage for identity using Keychain
class IdentityStore: ObservableObject {
    static let shared = IdentityStore()

    @Published var hasIdentity: Bool = false
    @Published var displayName: String?
    @Published var serverUrl: String = "https://coord.is"
    @Published var autoShareEnabled: Bool = false

    /// The user's marker color (what others see). Computed once from identity.
    private(set) lazy var myColor: String? = {
        guard let identity = getIdentity() else { return nil }
        return getIdentityColor(identity: identity)
    }()

    private let service = "sh.bentley.transponder"
    private let userDefaults = UserDefaults.standard

    init() {
        // First-launch detection: UserDefaults is cleared on uninstall, Keychain isn't.
        // If this is a fresh install but Keychain has stale data, clear it.
        let hasLaunchedBefore = userDefaults.bool(forKey: "hasLaunchedBefore")
        if !hasLaunchedBefore {
            // Clear any stale Keychain data from previous installation
            deleteKeychainData(key: "ed25519_private")
            deleteKeychainData(key: "ed25519_public")
            deleteKeychainData(key: "x25519_private")
            deleteKeychainData(key: "x25519_public")
            userDefaults.set(true, forKey: "hasLaunchedBefore")
        }

        hasIdentity = getIdentity() != nil
        displayName = userDefaults.string(forKey: "displayName")
        serverUrl = userDefaults.string(forKey: "serverUrl") ?? "https://coord.is"

        // Validate autoShareEnabled against actual permission state
        let storedAutoShare = userDefaults.object(forKey: "autoShareEnabled") as? Bool ?? false
        let hasAlwaysPermission = CLLocationManager().authorizationStatus == .authorizedAlways

        // If enabled but permission was revoked, reset it
        if storedAutoShare && !hasAlwaysPermission {
            userDefaults.set(false, forKey: "autoShareEnabled")
            autoShareEnabled = false
        } else {
            autoShareEnabled = storedAutoShare
        }
    }

    // MARK: - Identity Management

    func getIdentity() -> Identity? {
        guard let ed25519Private = getKeychainData(key: "ed25519_private"),
              let ed25519Public = getKeychainData(key: "ed25519_public"),
              let x25519Private = getKeychainData(key: "x25519_private"),
              let x25519Public = getKeychainData(key: "x25519_public") else {
            return nil
        }

        return Identity(
            ed25519Private: ed25519Private,
            ed25519Public: ed25519Public,
            x25519Private: x25519Private,
            x25519Public: x25519Public
        )
    }

    func saveIdentity(_ identity: Identity) {
        setKeychainData(key: "ed25519_private", data: identity.ed25519Private)
        setKeychainData(key: "ed25519_public", data: identity.ed25519Public)
        setKeychainData(key: "x25519_private", data: identity.x25519Private)
        setKeychainData(key: "x25519_public", data: identity.x25519Public)

        if Thread.isMainThread {
            self.hasIdentity = true
        } else {
            DispatchQueue.main.async {
                self.hasIdentity = true
            }
        }
    }

    func clearIdentity() {
        deleteKeychainData(key: "ed25519_private")
        deleteKeychainData(key: "ed25519_public")
        deleteKeychainData(key: "x25519_private")
        deleteKeychainData(key: "x25519_public")

        DispatchQueue.main.async {
            self.hasIdentity = false
        }
    }

    // MARK: - User Preferences

    func setDisplayName(_ name: String) {
        userDefaults.set(name, forKey: "displayName")
        DispatchQueue.main.async {
            self.displayName = name
        }
    }

    func setServerUrl(_ url: String) {
        userDefaults.set(url, forKey: "serverUrl")
        DispatchQueue.main.async {
            self.serverUrl = url
        }
    }

    func setAutoShareEnabled(_ enabled: Bool) {
        userDefaults.set(enabled, forKey: "autoShareEnabled")
        DispatchQueue.main.async {
            self.autoShareEnabled = enabled
        }
    }

    /// Migrate server URL from old domain to new domain if it matches
    func migrateServerUrl(from oldDomain: String, to newDomain: String) {
        if serverUrl.contains(oldDomain) {
            let newUrl = serverUrl.replacingOccurrences(of: oldDomain, with: newDomain)
            setServerUrl(newUrl)
            print("Migrated user server URL to \(newUrl)")
        }
    }

    /// Generate a shareable friend link for this identity
    var friendLink: String {
        guard let identity = getIdentity() else { return "" }
        let name = displayName ?? "Friend"
        return generateFriendLink(identity: identity, server: serverUrl, name: name)
    }

    // MARK: - Keychain Helpers

    private func setKeychainData(key: String, data: Data) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]

        // Delete existing item first
        SecItemDelete(query as CFDictionary)

        // Add new item
        var newQuery = query
        newQuery[kSecValueData as String] = data
        newQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        SecItemAdd(newQuery as CFDictionary, nil)
    }

    private func getKeychainData(key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess else {
            return nil
        }

        return result as? Data
    }

    private func deleteKeychainData(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]

        SecItemDelete(query as CFDictionary)
    }
}
