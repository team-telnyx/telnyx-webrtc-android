const fs = require('fs');
const path = require('path');

class ConfigManager {
    constructor() {
        this.configFile = path.join(__dirname, 'last-config.json');
    }

    loadConfig() {
        try {
            if (fs.existsSync(this.configFile)) {
                const data = fs.readFileSync(this.configFile, 'utf8');
                return JSON.parse(data);
            }
        } catch (error) {
            console.error('Error loading config:', error.message);
        }
        return null;
    }

    saveConfig(config) {
        try {
            fs.writeFileSync(this.configFile, JSON.stringify(config, null, 2));
            return true;
        } catch (error) {
            console.error('Error saving config:', error.message);
            return false;
        }
    }

    getDefaultConfig() {
        return {
            deviceToken: '',
            projectId: 'telnyx-webrtc-notifications',
            serviceAccountPath: 'telnyx-webrtc-notifications.json',
            serviceAccountJson: '',
            data: {
                caller_name: 'John Doe',
                caller_number: '+123456789'
            }
        };
    }

    validateConfig(config) {
        const missing = [];

        // Required fields
        if (!config.deviceToken || config.deviceToken.trim() === '') {
            missing.push('deviceToken');
        }

        if (!config.projectId || config.projectId.trim() === '') {
            missing.push('projectId');
        }

        if ((!config.serviceAccountPath || config.serviceAccountPath.trim() === '') && 
            (!config.serviceAccountJson || config.serviceAccountJson.trim() === '')) {
            missing.push('serviceAccountPath or serviceAccountJson');
        }

        if (missing.length > 0) {
            return { valid: false, missing };
        }

        if (!config.data || typeof config.data !== 'object') {
            return { valid: false, missing: ['data object'] };
        }

        const requiredData = ['caller_name', 'caller_number'];
        const missingData = requiredData.filter(field => !config.data[field] || config.data[field].trim() === '');
        
        if (missingData.length > 0) {
            return { valid: false, missing: missingData.map(field => `data.${field}`) };
        }

        return { valid: true };
    }
}

module.exports = ConfigManager;