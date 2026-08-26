import AuthenticationServices
import UIKit
import DetourShared

/// The browser half of signing in, which is all iOS has to supply: an
/// `ASWebAuthenticationSession` and 80 bytes from the system CSPRNG. The flow
/// itself — the authorize URL, PKCE, the state check, spending the code — is
/// shared `Oidc`, the same code the Android app runs.
///
/// `ASWebAuthenticationSession` rather than `SFSafariViewController` plus
/// `onOpenURL`: it hands the callback URL back to its caller in-process, so
/// there is no cross-screen state to keep. Android needs that state
/// (`PendingSignIn`) only because its redirect lands in the activity rather
/// than in the view holding the button.
@MainActor
final class SignIn: NSObject, ObservableObject {

    /// True from the moment the sheet is asked for until tokens are stored or
    /// the attempt is refused.
    @Published private(set) var busy = false

    /// The realm's own wording where there is any — shared `Oidc` throws
    /// `AuthException`, which crosses as an `NSError` carrying the message.
    @Published var error: String?

    /// False when no realm is configured, which is how a build shipping no
    /// baked issuer behaves until one is set under Settings.
    var configured: Bool { Oidc.shared.configured }

    /// Held for the life of the attempt: a session that is only a local in
    /// `present(_:)` can be released before it calls back.
    private var session: ASWebAuthenticationSession?

    func start() async {
        error = nil
        busy = true
        defer { busy = false }

        guard let secureEntropy = entropy() else {
            // SecRandomCopyBytes failing is undocumented on iOS, but checked
            // rather than trusted — see entropy()'s doc. Reported distinctly
            // from "no realm configured" below: proceeding with begin() here
            // would either refuse on length (a true but misleading "no
            // identity provider" message) or, if that guard were ever
            // loosened, sign in with a guessable verifier. Neither is what
            // actually happened, so the rider is told the truth instead.
            error = "Could not generate a secure sign-in request. Please try again."
            return
        }

        let authorize = Oidc.shared.begin(entropy: secureEntropy)
        guard !authorize.isEmpty, let url = URL(string: authorize) else {
            // begin() returns blank rather than throwing: it is not a suspend
            // function, and a throw out of one of those terminates this process
            // instead of arriving as an error. secureEntropy is always full
            // length by this point (entropy() guarantees it), so a blank
            // result here can only be the other thing begin() refuses on: no
            // realm configured.
            error = "No identity provider is configured, so there is nobody to "
                + "sign in to. Set the sign-in realm under Settings → Own server."
            return
        }

        // Two separate `do`/`catch` scopes, deliberately, rather than one
        // wrapping both calls: `abandon()` belongs only to the failures this
        // side caused. `present` failing means the browser never opened, so
        // nothing was ever handed the parked verifier — abandoning here is
        // the only place it gets cleared. `complete` failing means shared
        // `Oidc.spend` already ran and already decided whether to clear it
        // (see its doc: the state-mismatch path deliberately leaves it
        // parked, so a forged `detour://auth/callback` cannot kill a sign-in
        // genuinely in flight). Calling `abandon()` again after a `complete`
        // failure would undo that client-side. Do not merge these back into
        // one `catch`.
        let callback: URL
        do {
            callback = try await present(url)
        } catch is SignInDismissed {
            // Not a failure: the rider closed the sheet. Drop the parked
            // verifier so a later stale callback cannot be spent.
            Oidc.shared.abandon()
            return
        } catch let presentFailure {
            Oidc.shared.abandon()
            self.error = (presentFailure as NSError).localizedDescription
            return
        }

        do {
            try await Oidc.shared.complete(url: callback.absoluteString)
        } catch let completeFailure {
            self.error = (completeFailure as NSError).localizedDescription
        }
    }

    /// 80 bytes, the count shared `Oidc` asks for — 64 for the PKCE verifier
    /// and 16 for the state. `SecRandomCopyBytes`, never `Int.random`: both
    /// values have to be unguessable.
    ///
    /// `nil` only when `SecRandomCopyBytes` itself reports failure —
    /// documented never to happen on iOS, but checked rather than trusted,
    /// against `errSecSuccess` and nothing weaker. There is no path from
    /// here to `begin` with a short or partially filled buffer standing in
    /// for that failure: `out` is only ever built, full-length, after the
    /// success check below, and a caller that gets `nil` must not fall back
    /// to calling `begin` at all.
    private func entropy() -> KotlinByteArray? {
        let count = Int(Enums.shared.oidcEntropyBytes)
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else {
            return nil
        }
        // Kotlin/Native maps ByteArray to KotlinByteArray, which no Swift
        // Data bridge fills in — hence the copy, once per sign-in.
        let out = KotlinByteArray(size: Int32(count))
        for (index, byte) in bytes.enumerated() {
            out.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return out
    }

    private func present(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            // The scheme only, no "://" — and it is already registered in
            // Info.plist, though this API intercepts the redirect itself and
            // does not need it to be.
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: "detour"
            ) { [weak self] callback, failure in
                // The flow is over, one way or another, the moment this runs —
                // release the session here rather than holding it until the
                // next sign-in attempt overwrites `self.session`. Weak self,
                // not strong: a strong capture here would hold `self` (via
                // `self.session` below holding this very session, which holds
                // this closure) in a retain cycle for as long as the flow is
                // in flight.
                self?.session = nil
                if let callback {
                    continuation.resume(returning: callback)
                    return
                }
                if (failure as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    continuation.resume(throwing: SignInDismissed())
                    return
                }
                continuation.resume(throwing: failure ?? SignInDismissed())
            }
            session.presentationContextProvider = self
            self.session = session
            // A session that will not start never calls back, so resuming here
            // cannot double-resume — and its completion handler above never
            // runs to release `self.session`, so this branch clears it
            // directly instead.
            if !session.start() {
                self.session = nil
                continuation.resume(throwing: SignInDismissed())
            }
        }
    }

    /// A callback that arrived with no session waiting for it. Runs the shared
    /// refusal so the rider is told why, rather than nothing happening.
    static func reportOrphanedCallback(_ url: URL) async {
        do {
            try await Oidc.shared.complete(url: url.absoluteString)
        } catch {
            OrphanedSignIn.shared.message = (error as NSError).localizedDescription
        }
    }
}

/// The rider closed the sheet. Its own type so `start()` can tell a dismissal
/// from a refusal and stay silent about the former.
private struct SignInDismissed: Error {}

extension SignIn: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
            ?? ASPresentationAnchor()
    }
}

/// A sign-in callback that arrived in `DetourApp`'s `onOpenURL` rather than in
/// an `ASWebAuthenticationSession`'s own completion handler — the session was
/// already gone, because the app was killed behind the browser. One-shot,
/// same shape as `CircleNotifications.PendingCircleOpen`: `SignInForm` shows
/// the message once and clears it, the same way Android's `PendingSignIn`
/// carries `MainActivity`'s equivalent refusal to `FriendsScreen`.
@MainActor
final class OrphanedSignIn: ObservableObject {
    static let shared = OrphanedSignIn()
    private init() {}

    @Published var message: String?
}
