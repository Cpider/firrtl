import json
import re

class Preprocess:
    get_width = re.compile(r'[US]Int<(\d+)>')
    def __init__(self, log_dir):
        self.log_dir = log_dir
        self.top_dir = f"{self.log_dir}/ChipTop"
        self.json_port_connect = f"{self.top_dir}/ChipTop.connect.json"
        self.json_signal_width = f"{self.top_dir}/ChipTop.width.json"
        self.signal_width = dict()


    def __parse_json__(self, json_file):
        with open(json_file, "rt+") as f:
            json_data = f.read()
            return json.loads(json_data)
        
    def extract_log(self):
        signal_width = self.__parse_json__(self.json_signal_width)
        for mod, sigs in signal_width.items():
            self.signal_width[mod] = dict()
            for sn, wid_str in sigs.items():
                width = self.get_width.search(wid_str)
                # Type is Clock may not include
                if width:
                    self.signal_width[mod][sn] = (width.group(1), 'UInt') if 'UInt' in wid_str else (width.group(1), 'SInt')
        self.port_connect = self.__parse_json__(self.json_port_connect)