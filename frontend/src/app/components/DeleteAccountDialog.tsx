import { FormEvent, useEffect, useRef, useState } from "react";
import "../styles/delete-account.css";

export const DELETE_CONFIRMATION = "DELETE";

type Props = {
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

/**
 * Confirmation dialog for deleting the account. The delete button stays disabled until the user types DELETE.
 */
export default function DeleteAccountDialog({ busy, onConfirm, onCancel }: Props) {
  const [typed, setTyped] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const cancelRef = useRef(onCancel);
  cancelRef.current = onCancel;
  const confirmed = typed === DELETE_CONFIRMATION;

  useEffect(() => {
    inputRef.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) cancelRef.current();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [busy]);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (confirmed && !busy) onConfirm();
  };

  return (
    <div className="modal-backdrop delete-account-backdrop">
      <form
        className="glass card modal delete-account-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-account-title"
        aria-describedby="delete-account-body"
        onSubmit={submit}
      >
        <h3 id="delete-account-title" className="title">Delete your account?</h3>
        <div id="delete-account-body" className="delete-account-body">
          <p>This permanently removes:</p>
          <ul>
            <li>your profile and nickname</li>
            <li>your Elo rating and your place in the rankings</li>
            <li>your sign-in account, so you cannot log in with it again</li>
          </ul>
          <p>
            Games in progress are not ended. You are only removed from lobbies that have not started yet.
            This cannot be undone.
          </p>
        </div>
        <label className="delete-account-confirm">
          <span>Type {DELETE_CONFIRMATION} to confirm</span>
          <input
            ref={inputRef}
            className="input"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            disabled={busy}
          />
        </label>
        <div className="delete-account-actions">
          <button className="button secondary" type="button" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button className="button danger" type="submit" disabled={!confirmed || busy} aria-busy={busy}>
            {busy ? "Deleting..." : "Delete account"}
          </button>
        </div>
      </form>
    </div>
  );
}
