import { ArrowRight, BookOpenText, Compass, PackageSearch, ShoppingBag, Workflow } from 'lucide-react';
import { SCENARIO_EXAMPLES } from '../config/scenarioExamples';

const ICONS = { order: PackageSearch, product: ShoppingBag, knowledge: BookOpenText, general: Workflow };

export function ScenarioExamples({ disabled = false, onSelect }: {
  disabled?: boolean;
  onSelect: (question: string) => void;
}) {
  return <>
    <div className="home-section-heading">
      <span>选择服务能力</span>
      <small>点击示例填入输入框，确认后发送</small>
    </div>
    <div className="home-capability-grid">
      {SCENARIO_EXAMPLES.map((item, index) => {
        const Icon = ICONS[item.id];
        return <button type="button" key={item.id}
          className={`home-capability-card tone-${item.tone} animate-fade-in-up`}
          disabled={disabled}
          onClick={() => { if (!disabled) onSelect(item.question); }}
          aria-label={`${item.title}，填入示例：${item.question}`}
          style={{ animationDelay: `${index * 0.06}s` }}>
          <span className="home-capability-icon"><Icon size={21} /></span>
          <span className="home-capability-copy">
            <strong>{item.title}</strong>
            <small>{item.description}</small>
          </span>
          <span className="home-capability-example"><span>试着问</span>{item.question}</span>
          <span className="home-capability-action">填入问题 <ArrowRight className="home-card-arrow" size={16} /></span>
        </button>;
      })}
    </div>
    <div className="home-quick-row">
      <span className="home-quick-label"><Compass size={14} /> 示例提问</span>
      <div className="home-quick-actions">
        {SCENARIO_EXAMPLES.map(item => {
          const Icon = ICONS[item.id];
          return <button type="button" key={item.id} disabled={disabled}
            title={item.question} aria-label={`填入示例：${item.question}`}
            onClick={() => { if (!disabled) onSelect(item.question); }}>
            <Icon size={14} /> {item.shortLabel}
          </button>;
        })}
      </div>
    </div>
  </>;
}
