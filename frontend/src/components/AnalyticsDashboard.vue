<template>
  <div class="analytics-dashboard">
    <h1 class="dashboard-title">📊 智能建议数据分析面板</h1>
    
    <!-- 统计卡片 -->
    <div class="stats-cards">
      <div class="stat-card">
        <div class="stat-icon">👥</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalUsers }}</div>
          <div class="stat-label">活跃用户</div>
        </div>
      </div>
      
      <div class="stat-card">
        <div class="stat-icon">💬</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalConversations }}</div>
          <div class="stat-label">对话总数</div>
        </div>
      </div>
      
      <div class="stat-card">
        <div class="stat-icon">🎯</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.avgCTR }}%</div>
          <div class="stat-label">平均点击率</div>
        </div>
      </div>
      
      <div class="stat-card">
        <div class="stat-icon">⭐</div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.topIntent }}</div>
          <div class="stat-label">热门意图</div>
        </div>
      </div>
    </div>
    
    <!-- 图表区域 -->
    <div class="charts-container">
      <!-- A/B 测试结果对比 -->
      <div class="chart-card">
        <h3 class="chart-title">A/B 测试点击率对比</h3>
        <div ref="abTestChart" class="chart"></div>
      </div>
      
      <!-- 用户偏好分布 -->
      <div class="chart-card">
        <h3 class="chart-title">用户意图偏好分布</h3>
        <div ref="preferenceChart" class="chart"></div>
      </div>
      
      <!-- 建议点击趋势 -->
      <div class="chart-card full-width">
        <h3 class="chart-title">建议点击趋势（近7天）</h3>
        <div ref="trendChart" class="chart"></div>
      </div>
      
      <!-- 意图识别准确率 -->
      <div class="chart-card">
        <h3 class="chart-title">意图识别置信度分布</h3>
        <div ref="confidenceChart" class="chart"></div>
      </div>
      
      <!-- 热门建议 TOP 10 -->
      <div class="chart-card">
        <h3 class="chart-title">热门建议 TOP 10</h3>
        <div class="top-suggestions">
          <div 
            v-for="(item, index) in topSuggestions" 
            :key="index"
            class="suggestion-item"
          >
            <span class="rank">{{ index + 1 }}</span>
            <span class="text">{{ item.text }}</span>
            <span class="count">{{ item.clicks }} 次点击</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import * as echarts from 'echarts';

export default {
  name: 'AnalyticsDashboard',
  data() {
    return {
      stats: {
        totalUsers: 0,
        totalConversations: 0,
        avgCTR: 0,
        topIntent: '-'
      },
      topSuggestions: [],
      abTestChart: null,
      preferenceChart: null,
      trendChart: null,
      confidenceChart: null
    }
  },
  mounted() {
    this.loadStats();
    this.initCharts();
  },
  beforeUnmount() {
    // 销毁图表实例
    [this.abTestChart, this.preferenceChart, this.trendChart, this.confidenceChart]
      .forEach(chart => chart && chart.dispose());
  },
  methods: {
    /**
     * 加载统计数据
     */
    async loadStats() {
      try {
        // ⭐ 调用后端 API 获取真实统计数据
        const [statsResponse, suggestionsResponse] = await Promise.all([
          fetch('/api/analytics/stats'),
          fetch('/api/analytics/top-suggestions')
        ]);
        
        if (statsResponse.ok) {
          this.stats = await statsResponse.json();
        }
        
        if (suggestionsResponse.ok) {
          this.topSuggestions = await suggestionsResponse.json();
        }
        
      } catch (error) {
        console.error('加载统计数据失败:', error);
        // 使用默认值，避免页面空白
        this.stats = {
          totalUsers: 0,
          totalConversations: 0,
          avgCTR: 0,
          topIntent: 'N/A'
        };
        this.topSuggestions = [];
      }
    },
    
    /**
     * 初始化图表
     */
    initCharts() {
      this.initABTestChart();
      this.initPreferenceChart();
      this.initTrendChart();
      this.initConfidenceChart();
    },
    
    /**
     * A/B 测试图表
     */
    initABTestChart() {
      const chart = echarts.init(this.$refs.abTestChart);
      const option = {
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' }
        },
        legend: {
          data: ['Control 组', 'Variant 组']
        },
        xAxis: {
          type: 'category',
          data: ['建议1', '建议2', '建议3', '建议4', '建议5']
        },
        yAxis: {
          type: 'value',
          axisLabel: {
            formatter: '{value}%'
          }
        },
        series: [
          {
            name: 'Control 组',
            type: 'bar',
            data: [15, 12, 18, 10, 14],
            itemStyle: { color: '#5470c6' }
          },
          {
            name: 'Variant 组',
            type: 'bar',
            data: [22, 19, 25, 16, 21],
            itemStyle: { color: '#91cc75' }
          }
        ]
      };
      chart.setOption(option);
      this.abTestChart = chart;
    },
    
    /**
     * 用户偏好分布图
     */
    initPreferenceChart() {
      const chart = echarts.init(this.$refs.preferenceChart);
      const option = {
        tooltip: {
          trigger: 'item',
          formatter: '{b}: {c} ({d}%)'
        },
        legend: {
          orient: 'vertical',
          left: 'left'
        },
        series: [
          {
            name: '意图偏好',
            type: 'pie',
            radius: '60%',
            data: [
              { value: 450, name: '美食 FOOD' },
              { value: 320, name: '旅游 TRAVEL' },
              { value: 180, name: '天气 WEATHER' },
              { value: 120, name: '购物 SHOPPING' },
              { value: 80, name: '其他 GENERAL' }
            ],
            emphasis: {
              itemStyle: {
                shadowBlur: 10,
                shadowOffsetX: 0,
                shadowColor: 'rgba(0, 0, 0, 0.5)'
              }
            }
          }
        ]
      };
      chart.setOption(option);
      this.preferenceChart = chart;
    },
    
    /**
     * 点击趋势图
     */
    initTrendChart() {
      const chart = echarts.init(this.$refs.trendChart);
      const option = {
        tooltip: {
          trigger: 'axis'
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
        },
        yAxis: {
          type: 'value'
        },
        series: [
          {
            name: '点击次数',
            type: 'line',
            smooth: true,
            data: [120, 132, 101, 134, 190, 230, 210],
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(64, 158, 255, 0.5)' },
                { offset: 1, color: 'rgba(64, 158, 255, 0.05)' }
              ])
            },
            itemStyle: { color: '#409EFF' }
          }
        ]
      };
      chart.setOption(option);
      this.trendChart = chart;
    },
    
    /**
     * 置信度分布图
     */
    initConfidenceChart() {
      const chart = echarts.init(this.$refs.confidenceChart);
      const option = {
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' }
        },
        xAxis: {
          type: 'category',
          data: ['0.0-0.2', '0.2-0.4', '0.4-0.6', '0.6-0.8', '0.8-1.0']
        },
        yAxis: {
          type: 'value',
          name: '次数'
        },
        series: [
          {
            name: '识别次数',
            type: 'bar',
            data: [50, 120, 280, 450, 680],
            itemStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#83bff6' },
                { offset: 1, color: '#188df0' }
              ])
            }
          }
        ]
      };
      chart.setOption(option);
      this.confidenceChart = chart;
    }
  }
}
</script>

<style scoped>
.analytics-dashboard {
  padding: 24px;
  background: #f5f7fa;
  min-height: 100vh;
}

.dashboard-title {
  font-size: 28px;
  color: #303133;
  margin-bottom: 24px;
  text-align: center;
}

/* 统计卡片 */
.stats-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transition: transform 0.3s;
}

.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.stat-icon {
  font-size: 36px;
  margin-right: 16px;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #409EFF;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

/* 图表容器 */
.charts-container {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
  gap: 20px;
}

.chart-card {
  background: white;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.chart-card.full-width {
  grid-column: 1 / -1;
}

.chart-title {
  font-size: 18px;
  color: #303133;
  margin-bottom: 16px;
  font-weight: 500;
}

.chart {
  width: 100%;
  height: 300px;
}

/* 热门建议列表 */
.top-suggestions {
  max-height: 300px;
  overflow-y: auto;
}

.suggestion-item {
  display: flex;
  align-items: center;
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
  transition: background 0.3s;
}

.suggestion-item:hover {
  background: #f5f7fa;
}

.suggestion-item:last-child {
  border-bottom: none;
}

.rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #409EFF;
  color: white;
  font-weight: bold;
  margin-right: 12px;
  font-size: 14px;
}

.rank:nth-child(1) { background: #ffd700; }
.rank:nth-child(2) { background: #c0c0c0; }
.rank:nth-child(3) { background: #cd7f32; }

.text {
  flex: 1;
  color: #606266;
  font-size: 14px;
}

.count {
  color: #909399;
  font-size: 13px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .charts-container {
    grid-template-columns: 1fr;
  }
  
  .stats-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
