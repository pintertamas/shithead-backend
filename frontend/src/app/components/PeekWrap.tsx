import { ReactNode, useEffect, useRef, useState } from "react";

type Props = {
  className?: string;
  /** Accessible name of the toggle button. */
  label: string;
  /** Visible text of the toggle button. */
  toggleText: string;
  placement?: "above" | "below";
  popover: ReactNode;
  children: ReactNode;
};

/**
 * Wraps a table element with a peek popover. Fine pointers (mouse) see the popover on hover or
 * keyboard focus; touch devices use the toggle button, which is dismissed by tapping elsewhere.
 */
export default function PeekWrap({ className = "", label, toggleText, placement = "above", popover, children }: Props) {
  const [open, setOpen] = useState(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!popoverRef.current?.contains(target) && !toggleRef.current?.contains(target)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  return (
    <div className={`peek-wrap peek-${placement}${open ? " peek-open" : ""} ${className}`.trim()}>
      {children}
      <button
        ref={toggleRef}
        type="button"
        className="peek-toggle"
        aria-label={label}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {toggleText}
      </button>
      <div ref={popoverRef} className="peek-popover">{popover}</div>
    </div>
  );
}
