import Capacitor
import UserNotifications
import AudioToolbox
import AVFoundation

public class PushNotificationsHandler: NSObject, NotificationHandlerProtocol {
    public weak var plugin: CAPPlugin?
    var notificationRequestLookup = [String: JSObject]()
    static var player: AVAudioPlayer?
    var originalVolume: Float = 0.0

    public func requestPermissions(with completion: ((Bool, Error?) -> Void)? = nil) {
        var requestAuthorizationOptions: UNAuthorizationOptions = []
        if #available(iOS 12.0, *) {
            requestAuthorizationOptions = [.alert, .sound, .badge, .criticalAlert]
        } else {
            requestAuthorizationOptions = [.alert, .sound, .badge]
        }

        UNUserNotificationCenter.current().requestAuthorization(options: requestAuthorizationOptions) { granted, error in
            if let error = error {
                NSLog("An error occured \(error.localizedDescription)")
            }

            UNUserNotificationCenter.current().getNotificationSettings { settings in
                NSLog("Current notification settings \(settings.debugDescription)")
            }

            completion?(granted, error)
        }
    }

    public func checkPermissions(with completion: ((UNAuthorizationStatus) -> Void)? = nil) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            completion?(settings.authorizationStatus)
        }
    }

    public func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
        let notificationData = makeNotificationRequestJSObject(notification.request)
        self.plugin?.notifyListeners("pushNotificationReceived", data: notificationData)

        if let options = notificationRequestLookup[notification.request.identifier] {
            let silent = options["silent"] as? Bool ?? false

            if silent {
                return UNNotificationPresentationOptions.init(rawValue: 0)
            }
        }

        let criticalAlert = UserDefaults.standard.string(forKey: "criticalAlert") ?? "0"
        let sound = UserDefaults.standard.string(forKey: "notificationSound") ?? ""

        // Benutzer muss Lokal criticalAlert gesetzt haben + die Nachricht muss key criticalalert enthalten
        if criticalAlert == "1", let criticalInPayload = notification.request.content.userInfo["criticalalert"] as? String, criticalInPayload == "1" {
            if !sound.isEmpty {
                playCriticalSound(sound)
            }
            return [.alert, .badge]
        }

        if let optionsArray = self.plugin?.getConfig().getArray("presentationOptions") as? [String] {
            var presentationOptions = UNNotificationPresentationOptions.init()

            optionsArray.forEach { option in
                switch option {
                case "alert":
                    presentationOptions.insert(.alert)
                case "badge":
                    presentationOptions.insert(.badge)
                case "sound":
                    presentationOptions.insert(.sound)
                default:
                    print("Unrecognized presentation option: \(option)")
                }
            }

            return presentationOptions
        }

        return []
    }

    public func didReceive(response: UNNotificationResponse) {
        var data = JSObject()

        let originalNotificationRequest = response.notification.request
        let actionId = response.actionIdentifier

        if actionId == UNNotificationDefaultActionIdentifier {
            data["actionId"] = "tap"
        } else if actionId == UNNotificationDismissActionIdentifier {
            data["actionId"] = "dismiss"
        } else {
            data["actionId"] = actionId
        }

        if let inputType = response as? UNTextInputNotificationResponse {
            data["inputValue"] = inputType.userText
        }

        data["notification"] = makeNotificationRequestJSObject(originalNotificationRequest)

        self.plugin?.notifyListeners("pushNotificationActionPerformed", data: data, retainUntilConsumed: true)

    }

    func makeNotificationRequestJSObject(_ request: UNNotificationRequest) -> JSObject {
        return [
            "id": request.identifier,
            "title": request.content.title,
            "subtitle": request.content.subtitle,
            "badge": request.content.badge ?? 1,
            "body": request.content.body,
            "data": JSTypes.coerceDictionaryToJSObject(request.content.userInfo) ?? [:]
        ]
    }

    func playCriticalSound(_ soundName: String) {
        NSLog("PushNotificationsHandler: Playing critical sound")
        
        // Sound-Name von Dateiendung befreien, falls vorhanden
        let cleanSoundName = soundName.contains(".") ? String(soundName.prefix(upTo: soundName.lastIndex(of: ".") ?? soundName.endIndex)) : soundName
        
        guard let soundURL = Bundle.main.url(forResource: cleanSoundName, withExtension: "wav") else {
            NSLog("Sound file not found: \(cleanSoundName).wav")
            return
        }
        
        do {
            // Aktuelle Volume-Einstellungen speichern
            originalVolume = AVAudioSession.sharedInstance().outputVolume
            NSLog("PushNotificationsHandler: Original volume: \(originalVolume)")
            
            // Audio-Session für kritische Alarme konfigurieren - ähnlich zu Android USAGE_ALARM
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.overrideMutedMicrophoneInterruption])
            try AVAudioSession.sharedInstance().setActive(true)
            
            // Versuche Volume auf Maximum zu setzen (iOS Limitation: nur über MPVolumeView möglich, aber wir setzen den Player auf max)
            PushNotificationsHandler.player = try AVAudioPlayer(contentsOf: soundURL, fileTypeHint: AVFileType.wav.rawValue)
            PushNotificationsHandler.player?.volume = 1.0 // Maximum Volume für Player
            PushNotificationsHandler.player?.numberOfLoops = 0
            PushNotificationsHandler.player?.play()
            
            NSLog("PushNotificationsHandler: Critical sound started playing")
            
            let duration = getSoundFileDuration(soundURL)
            NSLog("PushNotificationsHandler: Sound duration: \(duration)ms")
            
            // Nach Sound-Dauer alles zurücksetzen (wie bei Android)
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(duration)) {
                self.stopCriticalSound()
            }
            
        } catch let error {
            NSLog("PushNotificationsHandler: Error playing critical sound: \(error.localizedDescription)")
        }
    }

    public func stopCriticalSound() {
        NSLog("PushNotificationsHandler: Stopping critical sound")
        
        if PushNotificationsHandler.player?.isPlaying == true {
            PushNotificationsHandler.player?.stop()
        }
        PushNotificationsHandler.player = nil
        
        // Audio-Session zurücksetzen
        do {
            try AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(false)
            NSLog("PushNotificationsHandler: Audio session reset")
        } catch let error {
            NSLog("PushNotificationsHandler: Error resetting audio session: \(error.localizedDescription)")
        }
    }

    public static func stopRingtone() {
        if player?.isPlaying == true {
            player?.stop()
        }
        player = nil
    }

    func getSoundFileDuration(_ url: URL) -> Int {
        do {
            let audioPlayer = try AVAudioPlayer(contentsOf: url)
            return Int(audioPlayer.duration * 1000)
        } catch {
            print("Failed to get sound duration: \(error.localizedDescription)")
            return 5000 // default duration
        }
    }
}
