import SwiftUI

enum Mood { case loading, sleep, idle, listen, think, talk, happy }

/// Holds the eyes' animation state. Updated once per frame from the TimelineView.
final class EyeAnimator {
    var mood: Mood = .loading {
        didSet { if mood != oldValue { moodSince = Date().timeIntervalSinceReferenceDate } }
    }
    /// 0...1 loudness, makes the eyes "breathe" while listening or talking
    var level: Double = 0
    var color = Color(red: 0.36, green: 0.88, blue: 1.0)

    private(set) var lookX = 0.0, lookY = 0.0, open = 0.1, scale = 1.0, happy = 0.0, bounce = 0.0, blink = 1.0
    private var tLookX = 0.0, tLookY = 0.0
    private var nextGlance = 0.0, nextBlink = 0.0, blinkStart = -1.0
    private var moodSince = 0.0, last = 0.0

    func update(now t: Double) {
        let dt = last == 0 ? 0.016 : min(0.1, t - last)
        last = t
        var tOpen = 1.0, tScale = 1.0, tHappy = 0.12
        bounce = 0

        switch mood {
        case .loading: tOpen = 0.35; tLookX = 0; tLookY = 0.5; tHappy = 0
        case .sleep: tOpen = 0.06 + 0.03 * sin(t * 1.2); tLookX = 0; tLookY = 0.3; tHappy = 0
        case .idle: glance(t)
        case .listen: tScale = 1.06 + level * 0.12; tLookX *= 0.9; tLookY = -0.05; tHappy = 0.05
        case .think: tOpen = 0.78; tHappy = 0; tLookX = 0.55 + 0.1 * sin(t * 0.7); tLookY = -0.55
        case .talk: tHappy = 0.35; tLookX *= 0.9; tLookY = 0; bounce = sin(t * 13) * (0.012 + level * 0.03)
        case .happy:
            tHappy = 0.85; tScale = 1.08; tLookX = 0; tLookY = -0.1
            if t - moodSince > 1.5 { mood = .idle }
        }

        // blinking
        if nextBlink == 0 { nextBlink = t + 2 }
        blink = 1
        if mood != .sleep && mood != .loading {
            if blinkStart < 0 && t >= nextBlink { blinkStart = t }
            if blinkStart >= 0 {
                let p = (t - blinkStart) / 0.15
                if p >= 1 {
                    blinkStart = -1
                    nextBlink = t + (Double.random(in: 0...1) < 0.2 ? 0.18 : Double.random(in: 2.5...6.5))
                } else { blink = abs(1 - 2 * p) }
            }
        }

        let k = min(1, dt * 9)
        lookX += (tLookX - lookX) * k
        lookY += (tLookY - lookY) * k
        open += (tOpen - open) * min(1, dt * 6)
        scale += (tScale - scale) * k
        happy += (tHappy - happy) * min(1, dt * 5)
    }

    private func glance(_ t: Double) {
        guard t >= nextGlance else { return }
        if Double.random(in: 0...1) < 0.45 { tLookX = 0; tLookY = 0 }
        else { tLookX = Double.random(in: -0.8...0.8); tLookY = Double.random(in: -0.5...0.5) }
        nextGlance = t + Double.random(in: 1.2...4.2)
    }
}

struct EyesView: View {
    let animator: EyeAnimator

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { ctx, size in
                let t = timeline.date.timeIntervalSinceReferenceDate
                animator.update(now: t)
                draw(&ctx, size: size, t: t)
            }
        }
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, t: Double) {
        let a = animator
        let w = size.width, h = size.height
        let eyeW = min(w * 0.17, h * 0.30) * a.scale
        let eyeH = eyeW * 1.55
        let gap = eyeW * 0.85
        let cx = w / 2 + a.lookX * w * 0.07
        let cy = h * 0.46 + a.lookY * h * 0.08 + a.bounce * h
        let openNow = max(0.04, a.open * a.blink)

        for side in [-1.0, 1.0] {
            let ex = cx + side * (gap / 2 + eyeW / 2)
            let s = 1 + side * a.lookX * 0.06
            let ew = eyeW * s, eh = eyeH * s * openNow
            let r = min(ew, eh) * 0.32
            let pad = ew * 0.12
            let glow = CGRect(x: ex - ew / 2 - pad, y: cy - eh / 2 - pad, width: ew + pad * 2, height: eh + pad * 2)
            ctx.fill(Path(roundedRect: glow, cornerRadius: r * 1.4), with: .color(a.color.opacity(0.16)))
            let eye = CGRect(x: ex - ew / 2, y: cy - eh / 2, width: ew, height: eh)
            ctx.fill(Path(roundedRect: eye, cornerRadius: r), with: .color(a.color))
            // happy cheeks: a dark oval pushing up from below
            if a.happy > 0.02 && openNow > 0.3 {
                let lift = eh * 0.55 * a.happy
                let cheek = CGRect(x: ex - ew * 0.9, y: cy + eh / 2 - lift, width: ew * 1.8, height: eh * 1.1)
                ctx.fill(Path(ellipseIn: cheek), with: .color(.black))
            }
        }

        if a.mood == .think {
            for i in 0..<3 {
                let alpha = 0.3 + 0.7 * max(0, sin(t * 5 - Double(i) * 0.8))
                let d = eyeW * 0.14
                let c = CGRect(x: w / 2 + Double(i - 1) * eyeW * 0.35 - d / 2, y: h * 0.80, width: d, height: d)
                ctx.fill(Path(ellipseIn: c), with: .color(a.color.opacity(alpha)))
            }
        }
    }
}
