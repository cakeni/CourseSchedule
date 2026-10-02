import { hvigor } from '@ohos/hvigor';
import { appTasks, OhosAppContext, OhosPluginId } from '@ohos/hvigor-ohos-plugin';

const { applyLocalSigning } = require('./scripts/local-signing.cjs');

hvigor.nodesEvaluated(() => {
  const context = hvigor.getRootNode().getContext(OhosPluginId.OHOS_APP_PLUGIN) as OhosAppContext;
  context.setBuildProfileOpt(applyLocalSigning(
    context.getBuildProfileOpt(), context.getProjectPath(), process.env,
    context.getCurrentProduct().getProductName()
  ));
});

export default {
  system: appTasks,
  plugins: []
};
