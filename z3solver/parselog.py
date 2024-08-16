import json

class Preprocess:
    def __init__(self, log_dir):
        self.log_dir = log_dir
        self.top_dir = f"{self.log_dir}/ChipTop"
        self.json_port_connect = f"{self.top_dir}/ChipTop.connect.json"
        self.json_signal_width = f"{self.top_dir}/ChipTop.width.json"


    def __parse_json__(self, json_file):
        with open(json_file, "rt+") as f:
            json_data = f.read()
            return json.loads(json_data)
        
    def extract_log(self):
        self.signal_width = self.__parse_json__(self.json_signal_width)
        self.port_connect = self.__parse_json__(self.json_port_connect)