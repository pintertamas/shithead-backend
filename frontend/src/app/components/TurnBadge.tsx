export default function TurnBadge({ name }: { name: string }) {
  return (
    <div className="turn">
      <span>Turn</span>
      <strong>{name}</strong>
    </div>
  );
}

