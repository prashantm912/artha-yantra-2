import * as echarts from 'echarts/core';
import {
  BarChart,
  CustomChart,
  HeatmapChart,
  LineChart,
  ParallelChart,
  ScatterChart,
} from 'echarts/charts';
import {
  BrushComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  ParallelComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

/**
 * Tree-shaken ECharts 5.6 registration (D4): only the chart types + components the analytics
 * surfaces use (fold bars, sweep scatter/parallel-coords/heatmap, Monte Carlo bands & histograms),
 * Canvas renderer. Confined to compare/analytics chunks via lazy routes (E-6 — ECharts never in the
 * initial bundle).
 */
echarts.use([
  BarChart,
  LineChart,
  ScatterChart,
  HeatmapChart,
  ParallelChart,
  CustomChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  VisualMapComponent,
  ParallelComponent,
  BrushComponent,
  TitleComponent,
  CanvasRenderer,
]);

export { echarts };
