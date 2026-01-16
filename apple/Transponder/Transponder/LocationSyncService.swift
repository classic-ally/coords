import Foundation
import CoreLocation

/// Service for syncing locations with the Transponder server
class LocationSyncService {
    private let identityStore: IdentityStore
    private let urlSession: URLSession

    init(identityStore: IdentityStore = .shared) {
        self.identityStore = identityStore
        self.urlSession = URLSession.shared
    }

    // MARK: - Result Types

    enum UploadResult {
        case success
        case error(String)
    }

    enum FetchResult {
        case success([FetchedLocation])
        case error(String)
    }

    enum ServerValidationResult {
        case valid(name: String, version: String)
        case invalidServer
        case networkError(String)
    }

    // MARK: - Server Validation

    func validateServer(url: String) async -> ServerValidationResult {
        let baseUrl = url.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let requestUrl = URL(string: "\(baseUrl)/api/version") else {
            return .invalidServer
        }

        do {
            let (data, response) = try await urlSession.data(from: requestUrl)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                return .invalidServer
            }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let name = json["name"] as? String,
                  let version = json["version"] as? String,
                  name == "coords" || name == "transponder" else {
                return .invalidServer
            }

            return .valid(name: name, version: version)
        } catch {
            return .networkError(error.localizedDescription)
        }
    }

    // MARK: - Add Friend with Location Upload

    enum AddFriendResult {
        case success                      // Friend added, no upload attempted
        case successWithUpload            // Friend added, upload succeeded
        case successUploadFailed(String)  // Friend added, but upload failed
        case addFriendFailed(String)      // Failed to add friend
    }

    /// Adds a friend and optionally uploads current location if auto-share is enabled
    func addFriendAndUploadLocation(
        pubkey: String,
        server: String,
        name: String,
        location: CLLocation?
    ) async -> AddFriendResult {
        // First, add the friend
        do {
            try addFriend(
                pubkey: pubkey,
                server: server,
                name: name,
                shareWith: true,
                fetchFrom: true
            )
        } catch {
            return .addFriendFailed(error.localizedDescription)
        }

        // Upload location if auto-share is enabled and we have location data
        if identityStore.autoShareEnabled, let location = location {
            let uploadResult = await uploadLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                accuracy: Float(location.horizontalAccuracy),
                timestamp: UInt64(location.timestamp.timeIntervalSince1970 * 1000)
            )
            switch uploadResult {
            case .success:
                return .successWithUpload
            case .error(let message):
                return .successUploadFailed(message)
            }
        }

        return .success
    }

    // MARK: - Location Upload

    func uploadLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: UInt64) async -> UploadResult {
        guard let identity = identityStore.getIdentity() else {
            return .error("No identity configured")
        }

        let serverUrl = identityStore.serverUrl

        let location = Location(
            latitude: latitude,
            longitude: longitude,
            accuracy: accuracy,
            timestamp: timestamp
        )

        // Get friends we share location with
        let shareRecipients = getShareRecipients()

        do {
            let request = try prepareLocationUpload(
                identity: identity,
                location: location,
                friends: shareRecipients,
                myServer: serverUrl
            )

            let result = await execute(request: request)

            switch result {
            case .success:
                markUploadSuccess(timestamp: timestamp)
                return .success
            case .httpError(let code, let message):
                return .error("Server error: \(code) \(message)")
            case .networkError(let message):
                return .error("Network error: \(message)")
            }
        } catch CoreError.StaleLocation {
            // Location is older than last upload, skip silently
            print("LocationSync: Skipping stale location upload")
            return .success
        } catch {
            return .error("Upload failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Location Fetch

    func fetchFriendLocations(friends: [Friend]) async -> FetchResult {
        guard let identity = identityStore.getIdentity() else {
            return .error("No identity configured")
        }

        if friends.isEmpty {
            return .success([])
        }

        do {
            let requests = prepareLocationFetch(friends: friends)
            var allLocations: [FetchedLocation] = []

            for request in requests {
                let result = await execute(request: request)
                if case .success(let data) = result {
                    let locations = try processFetchResponse(identity: identity, responseBody: data)
                    allLocations.append(contentsOf: locations)
                }
            }

            return .success(allLocations)
        } catch {
            return .error("Fetch failed: \(error.localizedDescription)")
        }
    }

    func fetchSelfLocation() async -> FetchResult {
        guard let identity = identityStore.getIdentity() else {
            return .error("No identity configured")
        }

        let serverUrl = identityStore.serverUrl
        let request = prepareSelfLocationFetch(identity: identity, server: serverUrl)

        let result = await execute(request: request)

        switch result {
        case .success(let data):
            do {
                let locations = try processFetchResponse(identity: identity, responseBody: data)
                return .success(locations)
            } catch {
                return .error("Failed to process response: \(error.localizedDescription)")
            }
        case .httpError(let code, let message):
            return .error("Server error: \(code) \(message)")
        case .networkError(let message):
            return .error("Network error: \(message)")
        }
    }

    /// Fetch locations for all friends we're tracking (fetch_from == true)
    /// Updates the cached locations in storage
    func fetchTrackedFriends() async -> FetchResult {
        guard let identity = identityStore.getIdentity() else {
            return .error("No identity configured")
        }

        let targets = getFetchTargets()
        if targets.isEmpty {
            return .success([])
        }

        do {
            let requests = prepareLocationFetch(friends: targets)
            var allLocations: [FetchedLocation] = []
            let now = UInt64(Date().timeIntervalSince1970 * 1000)

            for request in requests {
                let result = await execute(request: request)
                if case .success(let data) = result {
                    let locations = try processFetchResponse(identity: identity, responseBody: data)

                    // Update cached locations in storage
                    for loc in locations {
                        try? updateFriendLocation(
                            pubkey: loc.pubkey,
                            location: loc.location,
                            fetchedAt: now
                        )
                    }

                    allLocations.append(contentsOf: locations)
                }
            }

            return .success(allLocations)
        } catch {
            return .error("Fetch failed: \(error.localizedDescription)")
        }
    }

    // MARK: - HTTP Execution

    private enum HttpResult {
        case success(Data)
        case httpError(Int, String)
        case networkError(String)
    }

    private func execute(request: PreparedRequest) async -> HttpResult {
        guard let url = URL(string: request.url) else {
            return .networkError("Invalid URL")
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method

        for (key, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: key)
        }

        if !request.body.isEmpty {
            urlRequest.httpBody = request.body
        }

        do {
            let (data, response) = try await urlSession.data(for: urlRequest)

            guard let httpResponse = response as? HTTPURLResponse else {
                return .networkError("Invalid response")
            }

            if httpResponse.statusCode >= 200 && httpResponse.statusCode < 300 {
                return .success(data)
            } else {
                let message = HTTPURLResponse.localizedString(forStatusCode: httpResponse.statusCode)
                return .httpError(httpResponse.statusCode, message)
            }
        } catch {
            return .networkError(error.localizedDescription)
        }
    }
}
