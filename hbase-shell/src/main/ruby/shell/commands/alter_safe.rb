#
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

module Shell
  module Commands
    class AlterSafe < Command
      def help
        <<-EOF
Alter a table in a safe way.

This command will first create a temporary table with the updated schema to verify the schema
changes. It will proceed with the original table only if it's successful.

For example, this malformed command will not cause the original table to go down:

  hbase> alter_safe 't', { CONFIGURATION => { 'hbase.storescanner.pread.max.bytes' => 'x' } }

For other options, help 'alter'.
EOF
      end

      def command(table, *args)
        admin.alter_safe(table, true, *args)
      end
    end
  end
end
