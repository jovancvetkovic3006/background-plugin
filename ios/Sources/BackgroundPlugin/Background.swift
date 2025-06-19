import Foundation

@objc public class Background: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
