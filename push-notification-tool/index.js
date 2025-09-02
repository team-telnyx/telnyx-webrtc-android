#!/usr/bin/env node

const inquirer = require('inquirer');
const chalk = require('chalk');
const ConfigManager = require('./config-manager');
const PushSender = require('./push-sender');

class VoipPushTester {
    constructor() {
        this.configManager = new ConfigManager();
        this.pushSender = new PushSender();
    }

    displayTitle() {
        console.clear();
        console.log(chalk.blue.bold('\n🔔 Telnyx VoIP Push Notification Tester\n'));
        console.log(chalk.gray('═'.repeat(50)));
    }

    displayConfig(config) {
        console.log(chalk.yellow('\n📋 Current Configuration:'));
        console.log(chalk.gray('─'.repeat(30)));
        console.log(`Project ID: ${chalk.cyan(config.projectId || 'Not set')}`);
        
        if (config.serviceAccountJson) {
            console.log(`Service Account: ${chalk.cyan('JSON content provided')}`);
        } else if (config.serviceAccountPath) {
            console.log(`Service Account: ${chalk.cyan(config.serviceAccountPath)}`);
        } else {
            console.log(`Service Account: ${chalk.red('Not set')}`);
        }
        
        console.log(`Device Token: ${chalk.cyan(config.deviceToken ? '***' + config.deviceToken.slice(-8) : 'Not set')}`);
        console.log(`Priority: ${chalk.cyan('high (fixed)')}`);
        console.log(chalk.yellow('\nData:'));
        console.log(`  Type: ${chalk.cyan('voip (fixed)')}`);
        console.log(`  Call ID: ${chalk.cyan('87654321-dcba-4321-dcba-0987654321fe (fixed)')}`);
        console.log(`  Caller Name: ${chalk.cyan(config.data?.caller_name || 'Not set')}`);
        console.log(`  Caller Number: ${chalk.cyan(config.data?.caller_number || 'Not set')}`);
        console.log(`  Voice SDK ID: ${chalk.cyan('12345678-abcd-1234-abcd-1234567890ab (fixed)')}`);

        console.log(chalk.gray('─'.repeat(30)));
    }

    async getMainMenuChoice(hasConfig) {
        const choices = [];
        
        if (hasConfig) {
            choices.push(
                { name: '✅ Use previous configuration', value: 'use' },
                { name: '✏️  Update some values', value: 'update' },
                { name: '🔄 Start fresh with new configuration', value: 'fresh' }
            );
        } else {
            choices.push({ name: '🆕 Create new configuration', value: 'fresh' });
        }
        
        choices.push({ name: '❌ Exit', value: 'exit' });

        const { choice } = await inquirer.prompt([{
            type: 'list',
            name: 'choice',
            message: 'What would you like to do?',
            choices
        }]);

        return choice;
    }

    async promptForConfig(existingConfig = null) {
        const defaultConfig = existingConfig || this.configManager.getDefaultConfig();

        const questions = [
            {
                type: 'input',
                name: 'deviceToken',
                message: 'Device FCM Token:',
                default: defaultConfig.deviceToken,
                validate: input => input.trim() !== '' || 'Device token is required'
            },
            {
                type: 'input',
                name: 'projectId',
                message: 'Firebase Project ID:',
                default: defaultConfig.projectId,
                validate: input => input.trim() !== '' || 'Project ID is required'
            },
            {
                type: 'list',
                name: 'authMethod',
                message: 'Authentication method:',
                choices: [
                    { name: 'Service Account JSON content', value: 'json' },
                    { name: 'Service Account JSON file path', value: 'file' }
                ],
                default: 'json'
            },
            {
                type: 'input',
                name: 'caller_name',
                message: 'Caller Name:',
                default: defaultConfig.data?.caller_name || 'John Doe'
            },
            {
                type: 'input',
                name: 'caller_number',
                message: 'Caller Number:',
                default: defaultConfig.data?.caller_number || '+123456789'
            }
        ];

        const answers = await inquirer.prompt(questions);

        const config = {
            deviceToken: answers.deviceToken,
            projectId: answers.projectId,
            data: {
                caller_name: answers.caller_name,
                caller_number: answers.caller_number
            }
        };

        if (answers.authMethod === 'json') {
            const { serviceAccountJson } = await inquirer.prompt([{
                type: 'editor',
                name: 'serviceAccountJson',
                message: 'Paste your service account JSON content:',
                default: defaultConfig.serviceAccountJson || ''
            }]);
            config.serviceAccountJson = serviceAccountJson;
            config.serviceAccountPath = '';
        } else {
            const { serviceAccountPath } = await inquirer.prompt([{
                type: 'input',
                name: 'serviceAccountPath',
                message: 'Service account JSON file path:',
                default: defaultConfig.serviceAccountPath || '',
                validate: input => input.trim() !== '' || 'File path is required'
            }]);
            config.serviceAccountPath = serviceAccountPath;
            config.serviceAccountJson = '';
        }

        return config;
    }

    async promptForUpdates(existingConfig) {
        const updateChoices = [
            { name: 'Device Token', value: 'deviceToken' },
            { name: 'Project ID', value: 'projectId' },
            { name: 'Service Account', value: 'serviceAccount' },
            { name: 'Caller Name', value: 'caller_name' },
            { name: 'Caller Number', value: 'caller_number' }
        ];

        const { fieldsToUpdate } = await inquirer.prompt([{
            type: 'checkbox',
            name: 'fieldsToUpdate',
            message: 'Which fields would you like to update?',
            choices: updateChoices
        }]);

        if (fieldsToUpdate.length === 0) {
            return existingConfig;
        }

        const updatedConfig = { ...existingConfig };
        
        for (const field of fieldsToUpdate) {
            if (field === 'deviceToken' || field === 'projectId') {
                const { value } = await inquirer.prompt([{
                    type: 'input',
                    name: 'value',
                    message: `New ${field === 'deviceToken' ? 'Device Token' : 'Project ID'}:`,
                    default: updatedConfig[field],
                    validate: input => input.trim() !== '' || 'This field is required'
                }]);
                updatedConfig[field] = value;
            } else if (field === 'serviceAccount') {
                const { authMethod } = await inquirer.prompt([{
                    type: 'list',
                    name: 'authMethod',
                    message: 'Authentication method:',
                    choices: [
                        { name: 'Service Account JSON content', value: 'json' },
                        { name: 'Service Account JSON file path', value: 'file' }
                    ],
                    default: 'json'
                }]);

                if (authMethod === 'json') {
                    const { serviceAccountJson } = await inquirer.prompt([{
                        type: 'editor',
                        name: 'serviceAccountJson',
                        message: 'Paste your service account JSON content:',
                        default: updatedConfig.serviceAccountJson || ''
                    }]);
                    updatedConfig.serviceAccountJson = serviceAccountJson;
                    updatedConfig.serviceAccountPath = '';
                } else {
                    const { serviceAccountPath } = await inquirer.prompt([{
                        type: 'input',
                        name: 'serviceAccountPath',
                        message: 'Service account JSON file path:',
                        default: updatedConfig.serviceAccountPath || '',
                        validate: input => input.trim() !== '' || 'File path is required'
                    }]);
                    updatedConfig.serviceAccountPath = serviceAccountPath;
                    updatedConfig.serviceAccountJson = '';
                }
            } else {
                const fieldNames = {
                    'call_id': 'Call ID',
                    'caller_name': 'Caller Name', 
                    'caller_number': 'Caller Number',
                    'voice_sdk_id': 'Voice SDK ID'
                };
                const { value } = await inquirer.prompt([{
                    type: 'input',
                    name: 'value',
                    message: `New ${fieldNames[field] || field}:`,
                    default: updatedConfig.data[field]
                }]);
                updatedConfig.data[field] = value;
            }
        }

        return updatedConfig;
    }

    async confirmSend(config) {
        console.log(chalk.green('\n🚀 Ready to send push notification:'));
        this.displayConfig(config);

        const { confirm } = await inquirer.prompt([{
            type: 'confirm',
            name: 'confirm',
            message: 'Send the push notification?',
            default: true
        }]);

        return confirm;
    }

    async run() {
        try {
            this.displayTitle();
            
            const existingConfig = this.configManager.loadConfig();
            let currentConfig = existingConfig;

            if (existingConfig) {
                this.displayConfig(existingConfig);
            } else {
                console.log(chalk.yellow('\n📝 No previous configuration found.'));
            }

            const choice = await this.getMainMenuChoice(!!existingConfig);

            switch (choice) {
                case 'use':
                    currentConfig = existingConfig;
                    break;
                
                case 'update':
                    currentConfig = await this.promptForUpdates(existingConfig);
                    break;
                
                case 'fresh':
                    currentConfig = await this.promptForConfig();
                    break;
                
                case 'exit':
                    console.log(chalk.gray('\n👋 Goodbye!'));
                    process.exit(0);
                    break;
            }

            const validation = this.configManager.validateConfig(currentConfig);
            if (!validation.valid) {
                console.log(chalk.red('\n❌ Configuration is incomplete:'));
                validation.missing.forEach(field => {
                    console.log(chalk.red(`  - ${field} is required`));
                });
                process.exit(1);
            }

            this.configManager.saveConfig(currentConfig);
            
            const shouldSend = await this.confirmSend(currentConfig);
            if (shouldSend) {
                await this.pushSender.sendPushNotification(currentConfig);
            } else {
                console.log(chalk.gray('\n🚫 Push notification cancelled.'));
            }

            const { again } = await inquirer.prompt([{
                type: 'confirm',
                name: 'again',
                message: 'Would you like to send another notification?',
                default: false
            }]);

            if (again) {
                await this.run();
            } else {
                console.log(chalk.gray('\n👋 Goodbye!'));
            }

        } catch (error) {
            console.error(chalk.red('\n💥 An error occurred:'), error.message);
            process.exit(1);
        }
    }
}

if (require.main === module) {
    const tester = new VoipPushTester();
    tester.run();
}

module.exports = VoipPushTester;