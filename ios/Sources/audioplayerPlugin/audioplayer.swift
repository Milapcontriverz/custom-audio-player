import Foundation

@objc public class audioplayer: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
