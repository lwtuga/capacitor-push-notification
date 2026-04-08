import Capacitor
import UserNotifications
import AudioToolbox
import AVFoundation

public class PushNotificationsHandler: NSObject, NotificationHandlerProtocol {
    public weak var plugin: CAPPlugin?
    var notificationRequestLookup = [String: JSObject]()
    var player: AVAudioPlayer?

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

        if let critical = notification.request.content.userInfo["criticalalert"] as? String, critical == "1" {
            playSound(notification.request)
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

    func playSound(_ request: UNNotificationRequest) {
        if let soundName = request.content.userInfo["sound"] as? String {
            guard let soundURL = Bundle.main.url(forResource: soundName, withExtension: "wav") else {
                return
            }
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
                try AVAudioSession.sharedInstance().setActive(true)

                player = try AVAudioPlayer(contentsOf: soundURL, fileTypeHint: AVFileType.wav.rawValue)
                player?.volume = 1.0
                player?.play()
            } catch let error {
                print(error.localizedDescription)
            }
        }
    }
}
