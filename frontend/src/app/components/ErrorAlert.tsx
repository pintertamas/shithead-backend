import Icon from "./Icon";

type Props = {
  message: string | null;
  onDismiss?: () => void;
};

export default function ErrorAlert({ message, onDismiss }: Props) {
  if (!message) return null;

  return (
    <div className="error-alert" role="alert" aria-live="assertive">
      <span>{message}</span>
      {onDismiss && (
        <button className="error-alert-dismiss" type="button" onClick={onDismiss} aria-label="Dismiss error">
          <Icon name="close" size={16} />
        </button>
      )}
    </div>
  );
}
