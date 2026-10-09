import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Icon from "./Icon";
import "../styles/alerts.css";

const VISIBLE_MS = 3000;
const EXIT_MS = 220;

type ToastProps = {
  message: string | null;
  onDismiss?: () => void;
  variant: "error" | "success";
};

/**
 * Floating message toast. Rendered through a portal into document.body and
 * position: fixed, so showing it never changes the layout of the page under it.
 * Auto-hides after VISIBLE_MS (calling onDismiss so the caller clears its state),
 * restarts when a new message arrives, and pauses while hovered or focused.
 */
function Toast({ message, onDismiss, variant }: ToastProps) {
  const [text, setText] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [paused, setPaused] = useState(false);
  const [cycle, setCycle] = useState(0);
  const remaining = useRef(VISIBLE_MS);
  const startedAt = useRef(0);
  const dismissRef = useRef(onDismiss);
  dismissRef.current = onDismiss;

  // New message (or cleared by the caller): restart the countdown or start closing.
  useEffect(() => {
    if (!message) {
      setOpen(false);
      return;
    }
    remaining.current = VISIBLE_MS;
    setText(message);
    setPaused(false);
    setCycle((c) => c + 1);
    setOpen(true);
  }, [message]);

  // Countdown. Restarts on each new message (cycle) and resumes after a pause.
  useEffect(() => {
    if (!open || paused) return;
    startedAt.current = Date.now();
    const id = window.setTimeout(() => {
      setOpen(false);
      setPaused(false);
      dismissRef.current?.();
    }, remaining.current);
    return () => window.clearTimeout(id);
  }, [open, paused, cycle]);

  // Keep the last text mounted long enough for the exit animation, then unmount.
  useEffect(() => {
    if (open || !text) return;
    const id = window.setTimeout(() => setText(null), EXIT_MS);
    return () => window.clearTimeout(id);
  }, [open, text]);

  const pause = () => {
    if (open && !paused) {
      remaining.current = Math.max(0, remaining.current - (Date.now() - startedAt.current));
      setPaused(true);
    }
  };
  const resume = () => setPaused(false);

  const hide = () => {
    setOpen(false);
    setPaused(false);
    dismissRef.current?.();
  };

  if (!text) return null;

  const variantClass = variant === "error" ? "error-alert" : "success-alert";

  return createPortal(
    <div className="toast-layer">
      <div
        key={cycle}
        className={`toast-alert ${variantClass} ${open ? "is-open" : "is-closing"}`}
        role={variant === "error" ? "alert" : "status"}
        aria-live={variant === "error" ? "assertive" : "polite"}
        onPointerEnter={(e) => {
          if (e.pointerType === "mouse") pause();
        }}
        onPointerLeave={resume}
        onFocus={pause}
        onBlur={resume}
      >
        <span className="toast-alert-text">{text}</span>
        <button
          className="toast-alert-dismiss"
          type="button"
          onClick={hide}
          aria-label={variant === "error" ? "Dismiss error" : "Dismiss message"}
        >
          <Icon name="close" size={16} />
        </button>
      </div>
    </div>,
    document.body
  );
}

type Props = {
  message: string | null;
  onDismiss?: () => void;
};

export default function ErrorAlert({ message, onDismiss }: Props) {
  return <Toast message={message} onDismiss={onDismiss} variant="error" />;
}

export function SuccessAlert({ message, onDismiss }: Props) {
  return <Toast message={message} onDismiss={onDismiss} variant="success" />;
}
