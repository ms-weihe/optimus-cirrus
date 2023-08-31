/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ms.silverking.cloud.skfs.meta;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.ms.silverking.cloud.management.MetaToolModuleBase;
import com.ms.silverking.cloud.management.MetaToolOptions;
import com.ms.silverking.cloud.zookeeper.SilverKingZooKeeperClient.KeeperException;
import com.ms.silverking.io.StreamParser;
import org.apache.zookeeper.CreateMode;

public class SKFSConfigurationZK extends MetaToolModuleBase<SKFSConfiguration, MetaPaths> {
  public SKFSConfigurationZK(MetaClient mc) throws KeeperException {
    super(mc, mc.getMetaPaths().getConfigPath());
  }

  @Override
  public SKFSConfiguration readFromFile(File file, long version) throws IOException {
    com.ms.silverking.cloud.skfs.meta.MetaClient skfsMc =
        (com.ms.silverking.cloud.skfs.meta.MetaClient) (this.mc);
    return new SKFSConfiguration(
        skfsMc.getSKFSConfigName(), StreamParser.parseLines(file), version, 0L);
  }

  @Override
  public SKFSConfiguration readFromZK(long version, MetaToolOptions options)
      throws KeeperException {
    com.ms.silverking.cloud.skfs.meta.MetaClient skfsMc =
        (com.ms.silverking.cloud.skfs.meta.MetaClient) (this.mc);
    return SKFSConfiguration.parse(
        skfsMc.getSKFSConfigName(), zk.getString(getVBase(version)), version);
  }

  @Override
  public void writeToFile(File file, SKFSConfiguration instance) throws IOException {
    if (file != null) {
      FileOutputStream fs;
      fs = new FileOutputStream(file);
      fs.write(instance.toString().getBytes());
      fs.flush();
      fs.close();
    } else {
      System.out.println(instance.toString());
    }
  }

  @Override
  public String writeToZK(SKFSConfiguration dhtSKFSConfig, MetaToolOptions options)
      throws IOException, KeeperException {
    String path;

    path = zk.createString(base + "/", dhtSKFSConfig.toString(), CreateMode.PERSISTENT_SEQUENTIAL);
    return path;
  }
}
