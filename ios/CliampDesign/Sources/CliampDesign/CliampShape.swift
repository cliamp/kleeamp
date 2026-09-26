import Foundation

/// The concept's corner radii, one scale.
public enum CliampShape {
    public static let tiny: CGFloat = 4
    public static let small: CGFloat = 6
    public static let medium: CGFloat = 8
    public static let large: CGFloat = 20
    public static let key: CGFloat = 11
}

/// Screen gutter, fixed at 22 everywhere in the concept.
public let cliampGutter: CGFloat = 22

/// Width of the landscape tab rail, its separator included.
public let cliampTabRailWidth: CGFloat = 79
