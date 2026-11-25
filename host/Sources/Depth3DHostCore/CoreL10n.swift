import Foundation

/// Shared String Catalog localization helper for both `Depth3DHostCore` and `Depth3DHost`.
/// Every user-facing string (including protocol `/info` reasons and `426` messages) lives in
/// `Sources/Depth3DHostCore/Resources/Localizable.xcstrings`.
public enum HostStrings {
    private final class BundleToken {}

    public static let bundle: Bundle = {
        #if SWIFT_PACKAGE
        return Bundle.module
        #else
        return Bundle(for: BundleToken.self)
        #endif
    }()

    private static let zhHansBundle: Bundle? = {
        if let path = bundle.path(forResource: "zh-Hans", ofType: "lproj") {
            return Bundle(path: path)
        }
        return nil
    }()

    public static func string(_ key: String) -> String {
        let localized = NSLocalizedString(key, tableName: "Localizable", bundle: bundle, value: key, comment: "")
        if localized != key {
            return localized
        }
        if let zhBundle = zhHansBundle {
            return NSLocalizedString(key, tableName: "Localizable", bundle: zhBundle, value: key, comment: "")
        }
        return localized
    }

    public static func format(_ key: String, _ args: CVarArg...) -> String {
        let fmt = string(key)
        return String(format: fmt, locale: Locale(identifier: "zh_Hans_CN"), arguments: args)
    }
}
