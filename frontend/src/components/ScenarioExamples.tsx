import { ArrowUpLeft, BookOpenText, PackageSearch, ShoppingBag, Workflow } from 'lucide-react';
import { SCENARIO_EXAMPLES } from '../config/scenarioExamples';

const ICONS = { order: PackageSearch, product: ShoppingBag, knowledge: BookOpenText, general: Workflow };

export function ScenarioExamples({ disabled = false, onSelect }: {
  disabled?: boolean;
  onSelect: (question: string) => void;
}) {
  return <>
    <div className="home-section-heading">
      <span>从这些问题开始</span>
      <small>点击示例填入输入框，确认后发送</small>
    </div>
    <div className="home-capability-grid">
      {SCENARIO_EXAMPLES.map(item => {
        const Icon = ICONS[item.id];
        return <button type="button" key={item.id}
          className="home-capability-card"
          disabled={disabled}
          onClick={() => { if (!disabled) onSelect(item.question); }}
          aria-label={`${item.title}，填入示例：${item.question}`}>
          <span className="home-capability-icon"><Icon size={18} /></span>
          <span className="home-capability-copy">
            <strong>{item.title}</strong>
          </span>
          <span className="home-capability-example">{item.question}</span>
          <ArrowUpLeft className="home-card-arrow" size={16} aria-hidden="true" />
        </button>;
      })}
    </div>
  </>;
}
